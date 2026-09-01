from __future__ import annotations

import logging
import os
from datetime import datetime, timezone
from typing import Any
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.config import get_settings
from app.database import get_db
from app.models import Subscription, SubscriptionEvent
from app.sync import SyncIdentity, ensure_user_for_write, get_sync_user

logger = logging.getLogger("comics8.billing")
router = APIRouter(prefix="/api/comics8/subscription", tags=["Billing"])

_android_publisher_service = None


def get_android_publisher():
    """Initializes Google Play Developer API client using service account."""
    global _android_publisher_service
    if _android_publisher_service is not None:
        return _android_publisher_service

    settings = get_settings()
    cred_path = settings.GOOGLE_APPLICATION_CREDENTIALS
    if not cred_path or not os.path.isfile(cred_path):
        logger.warning(f"Google application credentials not found at {cred_path}.")
        return None

    try:
        from google.oauth2 import service_account
        from googleapiclient.discovery import build

        credentials = service_account.Credentials.from_service_account_file(
            cred_path,
            scopes=["https://www.googleapis.com/auth/androidpublisher"],
        )
        _android_publisher_service = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)
        logger.info("Google Play Developer API client initialized.")
        return _android_publisher_service
    except Exception as e:
        logger.error(f"Failed to initialize Google Play Developer API client: {e}")
        return None


def parse_rfc3339(date_str: str | None) -> datetime | None:
    if not date_str:
        return None
    try:
        # Handles 2026-08-26T09:00:00.000Z or +00:00
        clean_str = date_str.replace("Z", "+00:00")
        return datetime.fromisoformat(clean_str)
    except Exception:
        return None


async def verify_google_play_subscription_token(
    purchase_token: str,
) -> dict[str, Any]:
    """
    Calls Google Play Developer API purchases.subscriptionsv2.get.
    """
    settings = get_settings()
    service = get_android_publisher()

    if service is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Google Play billing verification is unavailable",
        )

    try:
        # Google Play API subscriptionsv2
        request = service.purchases().subscriptionsv2().get(
            packageName=settings.GOOGLE_PLAY_PACKAGE_NAME,
            token=purchase_token,
        )
        response = request.execute()
        return response
    except Exception as e:
        logger.error(f"Google Play API subscription verification error: {e}")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Google Play verification failed",
        )


async def acknowledge_google_play_subscription(
    purchase_token: str,
    product_id: str | None = None,
) -> None:
    settings = get_settings()
    service = get_android_publisher()
    if service is None:
        return

    try:
        # Older subscriptions endpoint or standard acknowledge
        if product_id:
            service.purchases().subscriptions().acknowledge(
                packageName=settings.GOOGLE_PLAY_PACKAGE_NAME,
                subscriptionId=product_id,
                token=purchase_token,
                body={},
            ).execute()
            logger.info(f"Acknowledged subscription token {purchase_token[:10]}...")
    except Exception as e:
        logger.warning(f"Error acknowledging subscription: {e}")


def is_subscription_active(state_str: str, expiry_time: datetime) -> bool:
    now = datetime.now(timezone.utc)
    # Active states according to Google Play
    if state_str in ("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"):
        return expiry_time > now
    if state_str == "SUBSCRIPTION_STATE_CANCELED":
        # Canceled subscriptions remain active until expiry
        return expiry_time > now
    return False


async def update_or_create_subscription(
    session: AsyncSession,
    user_id: str,
    purchase_token: str,
    play_data: dict[str, Any],
    product_id_hint: str | None = None,
    base_plan_id_hint: str | None = None,
) -> Subscription:
    line_items = play_data.get("lineItems", [])
    first_item = line_items[0] if line_items else {}

    product_id = first_item.get("productId") or product_id_hint or "comics8_pro"
    offer_details = first_item.get("offerDetails") or {}
    base_plan_id = offer_details.get("basePlanId") or base_plan_id_hint

    sub_state = play_data.get("subscriptionState", "SUBSCRIPTION_STATE_ACTIVE")
    expiry_time_str = first_item.get("expiryTime")
    expiry_time = parse_rfc3339(expiry_time_str) or datetime.now(timezone.utc)
    start_time = parse_rfc3339(play_data.get("startTime"))

    auto_renew_plan = first_item.get("autoRenewingPlan")
    auto_renewing = bool(auto_renew_plan.get("autoRenewEnabled", True)) if auto_renew_plan else True

    active = is_subscription_active(sub_state, expiry_time)
    latest_order_id = play_data.get("latestOrderId")
    ack_state = play_data.get("acknowledgementState", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED")

    # Transaction ID: use latestOrderId or purchaseToken
    original_tx_id = latest_order_id or purchase_token

    # Find existing subscription by token or tx id
    stmt = select(Subscription).where(
        (Subscription.purchase_token == purchase_token)
        | (Subscription.original_transaction_id == original_tx_id)
    )
    res = await session.execute(stmt)
    sub = res.scalar_one_or_none()

    if sub is None:
        sub = Subscription(
            user_id=user_id,
            original_transaction_id=original_tx_id,
            purchase_token=purchase_token,
            product_id=product_id,
            base_plan_id=base_plan_id,
            status=sub_state,
            is_active=active,
            auto_renewing=auto_renewing,
            start_time=start_time,
            expiry_time=expiry_time,
            acknowledgement_state=ack_state,
            latest_order_id=latest_order_id,
            raw_data=play_data,
        )
        session.add(sub)
    else:
        sub.user_id = user_id
        sub.purchase_token = purchase_token
        sub.product_id = product_id
        sub.base_plan_id = base_plan_id
        sub.status = sub_state
        sub.is_active = active
        sub.auto_renewing = auto_renewing
        sub.expiry_time = expiry_time
        if start_time:
            sub.start_time = start_time
        sub.acknowledgement_state = ack_state
        sub.latest_order_id = latest_order_id
        sub.raw_data = play_data

    # Record event
    event = SubscriptionEvent(
        user_id=user_id,
        original_transaction_id=original_tx_id,
        event_type=f"VERIFY_{sub_state}",
        purchase_token=purchase_token,
        raw_payload=play_data,
    )
    session.add(event)
    await session.commit()
    await session.refresh(sub)
    return sub


class SubscriptionVerifyRequest(BaseModel):
    purchaseToken: str = Field(..., description="Google Play purchase token")
    productId: str | None = Field(None, description="Product ID (SKU)")
    basePlanId: str | None = Field(None, description="Base plan ID")


class SubscriptionStatusResponse(BaseModel):
    userId: str
    isPro: bool
    subscription: dict[str, Any] | None = None


@router.post("/verify")
async def verify_subscription(
    payload: SubscriptionVerifyRequest,
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    """
    Verifies a Google Play subscription purchase token and grants Pro status to the user.
    """
    token = payload.purchaseToken.strip()
    if not token:
        raise HTTPException(status_code=400, detail="purchaseToken is required")

    play_data = await verify_google_play_subscription_token(
        purchase_token=token,
    )

    # Auto acknowledge if pending
    ack_state = play_data.get("acknowledgementState")
    if ack_state == "ACKNOWLEDGEMENT_STATE_PENDING_ACKNOWLEDGE":
        await acknowledge_google_play_subscription(
            purchase_token=token,
            product_id=payload.productId,
        )

    await ensure_user_for_write(session, identity)

    sub = await update_or_create_subscription(
        session=session,
        user_id=identity.user_id,
        purchase_token=token,
        play_data=play_data,
        product_id_hint=payload.productId,
        base_plan_id_hint=payload.basePlanId,
    )

    return {
        "success": True,
        "isPro": sub.is_active,
        "status": sub.status,
        "productId": sub.product_id,
        "basePlanId": sub.base_plan_id,
        "expiryTime": sub.expiry_time.isoformat() if sub.expiry_time else None,
        "autoRenewing": sub.auto_renewing,
        "userId": identity.user_id,
    }


@router.get("/status", response_model=SubscriptionStatusResponse)
async def get_subscription_status(
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    """
    Returns current subscription status and Pro privilege for the user.
    """
    now = datetime.now(timezone.utc)
    user_id = identity.user_id
    stmt = (
        select(Subscription)
        .where(
            Subscription.user_id == user_id,
            Subscription.is_active == True,
            Subscription.expiry_time > now,
        )
        .order_by(Subscription.expiry_time.desc())
    )
    res = await session.execute(stmt)
    active_sub = res.scalar_one_or_none()

    if active_sub:
        return SubscriptionStatusResponse(
            userId=user_id,
            isPro=True,
            subscription={
                "productId": active_sub.product_id,
                "basePlanId": active_sub.base_plan_id,
                "status": active_sub.status,
                "expiryTime": active_sub.expiry_time.isoformat(),
                "autoRenewing": active_sub.auto_renewing,
                "startTime": active_sub.start_time.isoformat() if active_sub.start_time else None,
            },
        )

    # Check for most recent subscription (even if expired)
    recent_stmt = (
        select(Subscription)
        .where(Subscription.user_id == user_id)
        .order_by(Subscription.updated_at.desc())
    )
    recent_res = await session.execute(recent_stmt)
    last_sub = recent_res.scalar_one_or_none()

    return SubscriptionStatusResponse(
        userId=user_id,
        isPro=False,
        subscription={
            "productId": last_sub.product_id,
            "basePlanId": last_sub.base_plan_id,
            "status": last_sub.status,
            "expiryTime": last_sub.expiry_time.isoformat() if last_sub.expiry_time else None,
            "autoRenewing": last_sub.auto_renewing,
        } if last_sub else None,
    )
