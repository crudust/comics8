from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import threading
from datetime import datetime, timezone
from typing import Any
from sqlalchemy import select
from app.config import get_settings
from app.database import get_session_factory
from app.models import Subscription, SubscriptionEvent, User

logger = logging.getLogger("comics8.pubsub")

_RTDN_NOTIFICATION_TYPES = {
    1: "SUBSCRIPTION_RECOVERED",
    2: "SUBSCRIPTION_RENEWED",
    3: "SUBSCRIPTION_CANCELED",
    4: "SUBSCRIPTION_PURCHASED",
    5: "SUBSCRIPTION_ON_HOLD",
    6: "SUBSCRIPTION_IN_GRACE_PERIOD",
    7: "SUBSCRIPTION_RESTARTED",
    8: "SUBSCRIPTION_PRICE_CHANGE_CONFIRMED",
    9: "SUBSCRIPTION_DEFERRED",
    10: "SUBSCRIPTION_PAUSED",
    11: "SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED",
    12: "SUBSCRIPTION_REVOKED",
    13: "SUBSCRIPTION_EXPIRED",
}

_streaming_pull_future = None
_subscriber_client = None


async def process_rtdn_payload(payload: dict[str, Any], session: AsyncSession | None = None) -> None:
    """Processes Google Play Real-Time Developer Notification payload."""
    test_notification = payload.get("testNotification")
    if test_notification:
        logger.info("Received Google Play test notification.")
        return

    sub_notification = payload.get("subscriptionNotification")
    if not sub_notification:
        logger.info(f"Received non-subscription notification: {payload}")
        return

    notification_type_int = sub_notification.get("notificationType")
    notification_type_str = _RTDN_NOTIFICATION_TYPES.get(
        notification_type_int,
        f"UNKNOWN_{notification_type_int}",
    )
    purchase_token = sub_notification.get("purchaseToken")
    subscription_id = sub_notification.get("subscriptionId")

    logger.info(
        f"Processing RTDN event: {notification_type_str} for SKU: {subscription_id}, "
        f"Token: {purchase_token[:10] if purchase_token else 'N/A'}..."
    )

    if not purchase_token:
        return

    # 1. Fetch latest details from Google Play Developer API
    from app.billing import update_or_create_subscription, verify_google_play_subscription_token

    try:
        play_data = await verify_google_play_subscription_token(
            purchase_token=purchase_token,
            product_id=subscription_id,
        )
    except Exception as e:
        logger.error(f"Failed to query Google Play API during RTDN processing: {e}")
        play_data = {}

    # 2. Database update
    async def _handle_db(db_sess: AsyncSession):
        # Find existing subscription by token
        stmt = select(Subscription).where(Subscription.purchase_token == purchase_token)
        res = await db_sess.execute(stmt)
        sub = res.scalar_one_or_none()

        user_id = sub.user_id if sub else None

        if sub and play_data:
            await update_or_create_subscription(
                session=db_sess,
                user_id=sub.user_id,
                purchase_token=purchase_token,
                play_data=play_data,
                product_id_hint=subscription_id,
            )
            logger.info(f"Updated subscription for user {sub.user_id}: {sub.status} (active={sub.is_active})")
        else:
            # Record audit event even if user is not yet associated
            event = SubscriptionEvent(
                user_id=user_id,
                original_transaction_id=purchase_token,
                event_type=f"RTDN_{notification_type_str}",
                notification_type=notification_type_int,
                purchase_token=purchase_token,
                raw_payload=payload,
            )
            db_sess.add(event)
            await db_sess.commit()
            logger.info(f"Logged RTDN event without linked user: {notification_type_str}")

    if session is not None:
        await _handle_db(session)
    else:
        factory = get_session_factory()
        async with factory() as db_sess:
            await _handle_db(db_sess)


def _pubsub_message_callback(message):
    """Sync callback executed by Google Cloud Pub/Sub client."""
    try:
        data_str = message.data.decode("utf-8")
        payload = json.loads(data_str)
        logger.debug(f"Received Pub/Sub message: {data_str}")

        # Run async processor on event loop
        loop = asyncio.get_event_loop_policy().get_event_loop()
        if loop.is_running():
            asyncio.run_coroutine_threadsafe(process_rtdn_payload(payload), loop)
        else:
            asyncio.run(process_rtdn_payload(payload))

        message.ack()
    except Exception as e:
        logger.error(f"Error handling Pub/Sub message: {e}", exc_info=True)
        # Nack on failure to allow retry
        try:
            message.nack()
        except Exception:
            pass


def start_pubsub_worker():
    """Starts the background Google Cloud Pub/Sub Streaming Pull subscriber."""
    global _streaming_pull_future, _subscriber_client
    settings = get_settings()

    subscription_path = settings.pubsub_subscription
    cred_path = settings.GOOGLE_APPLICATION_CREDENTIALS

    if not subscription_path:
        logger.info("PUBSUB_SUBSCRIPTION_ID not set. Pub/Sub streaming pull worker skipped.")
        return

    if not cred_path or not os.path.isfile(cred_path):
        logger.warning(f"Google application credentials ({cred_path}) not found. Pub/Sub worker skipped.")
        return

    try:
        from google.cloud import pubsub_v1
        from google.oauth2 import service_account

        credentials = service_account.Credentials.from_service_account_file(cred_path)
        _subscriber_client = pubsub_v1.SubscriberClient(credentials=credentials)

        logger.info(f"Starting Google Cloud Pub/Sub Streaming Pull for {subscription_path}...")
        _streaming_pull_future = _subscriber_client.subscribe(
            subscription_path,
            callback=_pubsub_message_callback,
        )
        logger.info("Google Cloud Pub/Sub worker successfully started.")
    except Exception as e:
        logger.error(f"Failed to start Google Cloud Pub/Sub worker: {e}")


def stop_pubsub_worker():
    """Stops the background Pub/Sub worker cleanly."""
    global _streaming_pull_future, _subscriber_client
    if _streaming_pull_future is not None:
        try:
            logger.info("Stopping Google Cloud Pub/Sub worker...")
            _streaming_pull_future.cancel()
            _streaming_pull_future = None
        except Exception as e:
            logger.warning(f"Error cancelling Pub/Sub future: {e}")

    if _subscriber_client is not None:
        try:
            _subscriber_client.close()
            _subscriber_client = None
        except Exception as e:
            logger.warning(f"Error closing Pub/Sub subscriber client: {e}")
    logger.info("Google Cloud Pub/Sub worker stopped.")
