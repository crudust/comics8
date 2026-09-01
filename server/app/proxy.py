from __future__ import annotations

import asyncio
import ipaddress
import logging
import re
import socket
from dataclasses import dataclass
from typing import AsyncGenerator
from urllib.parse import urljoin, urlparse
import httpx
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response as RawResponse, StreamingResponse
from app.config import get_settings
from app.sync import SyncIdentity, get_existing_sync_user

logger = logging.getLogger("comics8.proxy")
router = APIRouter(tags=["Proxy"])

_EXACT_PROXY_HOSTS = {
    "hitomi.la",
    "tn.hitomi.la",
    "11toon.com",
    "11toon7.com",
    "11toon8.com",
    "www.11toon.com",
    "www.11toon7.com",
    "www.11toon8.com",
    "103.204.13.68",
}
_PL_HOST_RE = re.compile(r"(www\.)?pl\d{4}\.com\Z")
_TOON_HOST_RE = re.compile(r"(www\.)?11toon\d*\.com\Z")
PROXY_REDIRECT_STATUSES = {301, 302, 303, 307, 308}


@dataclass(frozen=True)
class ResolvedProxyTarget:
    pinned_url: str
    host_header: str
    sni_hostname: str | None


def create_proxy_client() -> httpx.AsyncClient:
    settings = get_settings()
    return httpx.AsyncClient(
        timeout=httpx.Timeout(settings.PROXY_TIMEOUT_SECONDS, connect=10.0),
        follow_redirects=False,
    )


def create_proxy_semaphore() -> asyncio.Semaphore:
    return asyncio.Semaphore(get_settings().MAX_PROXY_CONCURRENT_REQUESTS)


def is_allowed_proxy_host(host: str | None) -> bool:
    if not host:
        return False
    normalized = host.lower().strip().strip(".")
    if not normalized:
        return False
    if normalized in _EXACT_PROXY_HOSTS:
        return True
    if normalized.endswith(".gold-usergeneratedcontent.net"):
        return True
    if normalized == "hitomi.la" or normalized.endswith(".hitomi.la"):
        return True
    if _TOON_HOST_RE.fullmatch(normalized):
        return True
    if _PL_HOST_RE.fullmatch(normalized):
        return True
    return False


def allowed_proxy_url(url: str | None) -> bool:
    if not url:
        return False
    try:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not is_allowed_proxy_host(parsed.hostname):
            return False
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        if parsed.hostname == "103.204.13.68" and parsed.scheme == "http" and port == 8904:
            return True
        return (parsed.scheme, port) in {("http", 80), ("https", 443)}
    except Exception:
        return False


def next_proxy_url(current_url: str, location: str | None) -> str | None:
    if not location or not str(location).strip():
        return None
    nxt = urljoin(current_url, str(location).strip())
    if not allowed_proxy_url(nxt):
        return None
    return nxt


def _is_public_address(raw_address: str) -> bool:
    address = ipaddress.ip_address(raw_address)
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        address = address.ipv4_mapped
    return address.is_global


async def resolve_proxy_target(url: str) -> ResolvedProxyTarget:
    parsed = urlparse(url)
    host = parsed.hostname
    if not allowed_proxy_url(url) or not host or parsed.username or parsed.password:
        raise PermissionError("Host not allowed")
    try:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
    except ValueError as exc:
        raise PermissionError("Invalid target port") from exc

    try:
        answers = await asyncio.to_thread(
            socket.getaddrinfo,
            host,
            port,
            type=socket.SOCK_STREAM,
        )
    except OSError as exc:
        raise ConnectionError("Target DNS lookup failed") from exc
    addresses = list(dict.fromkeys(answer[4][0] for answer in answers))
    if not addresses:
        raise ConnectionError("Target DNS lookup returned no addresses")
    if any(not _is_public_address(address) for address in addresses):
        raise PermissionError("Target resolved to a non-public address")

    selected = addresses[0]
    pinned = httpx.URL(url).copy_with(host=selected)
    display_host = f"[{host}]" if ":" in host else host
    host_header = f"{display_host}:{port}" if parsed.port is not None else display_host
    return ResolvedProxyTarget(
        pinned_url=str(pinned),
        host_header=host_header,
        sni_hostname=host.encode("idna").decode("ascii") if parsed.scheme == "https" else None,
    )


def build_proxy_forward_headers(
    incoming_headers: dict[str, str],
    referer_param: str | None = None,
    include_accept: bool = True,
) -> dict[str, str]:
    settings = get_settings()
    user_agent = incoming_headers.get("user-agent") or settings.DEFAULT_PROXY_UA

    headers = {
        "User-Agent": user_agent,
        "Accept-Encoding": "identity",
    }
    if include_accept:
        headers["Accept"] = incoming_headers.get("accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        headers["Accept-Language"] = incoming_headers.get("accept-language", "ko-KR,ko;q=0.9,en;q=0.8")

    referer = (
        (referer_param or "").strip()
        or (incoming_headers.get("referer") or "").strip()
        or settings.DEFAULT_REFERER
    )
    if referer:
        headers["Referer"] = referer

    range_header = incoming_headers.get("range")
    if range_header:
        headers["Range"] = range_header

    return headers


def passthrough_proxy_headers(resp_headers: httpx.Headers | dict[str, str]) -> dict[str, str]:
    out: dict[str, str] = {}
    content_type = resp_headers.get("content-type") or "text/html"
    out["Content-Type"] = content_type

    for name in ("Content-Range", "Content-Length", "Content-Encoding", "Accept-Ranges"):
        val = resp_headers.get(name) or resp_headers.get(name.lower())
        if val:
            out[name] = val
    return out


async def handle_proxy_request(request: Request, is_head: bool = False):
    settings = get_settings()
    url_param = request.query_params.get("url", "").strip()
    referer_param = request.query_params.get("referer", "").strip()

    if not url_param or not (url_param.startswith("http://") or url_param.startswith("https://")):
        if is_head:
            return RawResponse(status_code=400)
        return JSONResponse(
            status_code=400,
            content={
                "error": "BadRequest",
                "message": "Missing or invalid 'url' parameter (must start with http:// or https://)",
            },
        )

    if not allowed_proxy_url(url_param):
        if is_head:
            return RawResponse(status_code=403)
        return JSONResponse(
            status_code=403,
            content={"error": "Forbidden", "message": "Host not allowed"},
        )

    incoming_headers = {k.lower(): v for k, v in request.headers.items()}
    req_headers = build_proxy_forward_headers(
        incoming_headers=incoming_headers,
        referer_param=referer_param,
        include_accept=not is_head,
    )

    current_url = url_param
    method = "HEAD" if is_head else "GET"

    client: httpx.AsyncClient = request.app.state.proxy_client
    semaphore: asyncio.Semaphore = request.app.state.proxy_semaphore
    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.1)
    except TimeoutError:
        return RawResponse(status_code=503) if is_head else JSONResponse(
            status_code=503,
            content={"error": "ServiceUnavailable", "message": "Proxy is at capacity"},
        )
    semaphore_released = False

    def release_semaphore() -> None:
        nonlocal semaphore_released
        if not semaphore_released:
            semaphore.release()
            semaphore_released = True

    resp: httpx.Response | None = None
    try:
        for redirect_count in range(settings.MAX_PROXY_REDIRECTS + 1):
            target = await resolve_proxy_target(current_url)
            target_headers = {**req_headers, "Host": target.host_header, "Connection": "close"}
            req = client.build_request(method, target.pinned_url, headers=target_headers)
            if target.sni_hostname is not None:
                req.extensions["sni_hostname"] = target.sni_hostname
            resp = await client.send(req, stream=not is_head)

            if resp.status_code in PROXY_REDIRECT_STATUSES:
                if redirect_count >= settings.MAX_PROXY_REDIRECTS:
                    await resp.aclose()
                    release_semaphore()
                    return RawResponse(status_code=502) if is_head else JSONResponse(
                        status_code=502,
                        content={"error": "BadGateway", "message": "Too many redirects"},
                    )
                location = resp.headers.get("Location")
                nxt = next_proxy_url(current_url, location)
                await resp.aclose()
                if nxt is None:
                    release_semaphore()
                    if is_head:
                        return RawResponse(status_code=403)
                    return JSONResponse(status_code=403, content={"error": "Forbidden", "message": "Host not allowed"})
                if resp.status_code == 303:
                    method = "GET"
                current_url = nxt
                continue
            break

        if resp is None:
            release_semaphore()
            if is_head:
                return RawResponse(status_code=502)
            return JSONResponse(status_code=502, content={"error": "BadGateway", "message": "Proxy request failed"})

        out_headers = passthrough_proxy_headers(resp.headers)
        out_headers["Access-Control-Allow-Origin"] = "*"

        content_length = resp.headers.get("content-length")
        if content_length and not is_head:
            try:
                if int(content_length) > settings.MAX_PROXY_RESPONSE_BYTES:
                    await resp.aclose()
                    release_semaphore()
                    return RawResponse(status_code=413) if is_head else JSONResponse(
                        status_code=413,
                        content={"error": "ContentTooLarge", "message": "Proxy response is too large"},
                    )
            except ValueError:
                out_headers.pop("Content-Length", None)

        if is_head:
            status_code = resp.status_code
            await resp.aclose()
            release_semaphore()
            return RawResponse(status_code=status_code, headers=out_headers)

        async def stream_content() -> AsyncGenerator[bytes, None]:
            sent = 0
            try:
                async with asyncio.timeout(settings.MAX_PROXY_STREAM_SECONDS):
                    async for chunk in resp.aiter_raw(chunk_size=65536):
                        sent += len(chunk)
                        if sent > settings.MAX_PROXY_RESPONSE_BYTES:
                            raise RuntimeError("Proxy response exceeded the configured limit")
                        yield chunk
            finally:
                await resp.aclose()
                release_semaphore()

        return StreamingResponse(
            stream_content(),
            status_code=resp.status_code,
            headers=out_headers,
        )

    except asyncio.CancelledError:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        raise
    except PermissionError as exc:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        if is_head:
            return RawResponse(status_code=403)
        return JSONResponse(status_code=403, content={"error": "Forbidden", "message": str(exc)})
    except httpx.TimeoutException:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        if is_head:
            return RawResponse(status_code=504)
        return JSONResponse(
            status_code=504,
            content={"error": "GatewayTimeout", "message": "Target server timed out"},
        )
    except Exception as e:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        logger.error(f"Proxy error for {url_param}: {e}")
        if is_head:
            return RawResponse(status_code=502)
        return JSONResponse(
            status_code=502,
            content={"error": "BadGateway", "message": "Proxy request failed"},
        )


@router.get("/proxy")
@router.get("/proxy/proxy")
@router.get("/api/comics8/proxy")
@router.get("/api/comics8/proxy/proxy")
async def proxy_get(
    request: Request,
    _identity: SyncIdentity = Depends(get_existing_sync_user),
):
    return await handle_proxy_request(request, is_head=False)


@router.head("/proxy")
@router.head("/proxy/proxy")
@router.head("/api/comics8/proxy")
@router.head("/api/comics8/proxy/proxy")
async def proxy_head(
    request: Request,
    _identity: SyncIdentity = Depends(get_existing_sync_user),
):
    return await handle_proxy_request(request, is_head=True)
