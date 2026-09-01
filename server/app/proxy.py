from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from dataclasses import dataclass
import ipaddress
import logging
import socket
import threading
import time
from typing import AsyncGenerator
from urllib.parse import urljoin, urlparse
import httpx
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response as RawResponse, StreamingResponse
from app.config import get_settings
from app.sync import SyncIdentity, get_existing_sync_user

logger = logging.getLogger("comics8.proxy")
router = APIRouter(tags=["Proxy"])

PROXY_REDIRECT_STATUSES = {301, 302, 303, 307, 308}

# Allowed MIME types for web page and API scraping / parsing
_ALLOWED_CONTENT_TYPES = {
    "text/html",
    "text/plain",
    "text/xml",
    "text/javascript",
    "text/css",
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/x-javascript",
}


class ProxyRateLimiter:
    """Thread-safe in-memory sliding window rate limiter per sync user."""

    def __init__(self, limit_per_minute: int = 120, window_seconds: float = 60.0):
        self.limit = limit_per_minute
        self.window = window_seconds
        self._records: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def acquire(self, key: str) -> bool:
        if self.limit <= 0:
            return True
        now = time.monotonic()
        with self._lock:
            q = self._records[key]
            cutoff = now - self.window
            while q and q[0] <= cutoff:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            return True


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


def create_proxy_rate_limiter() -> ProxyRateLimiter:
    return ProxyRateLimiter(get_settings().PROXY_RATE_LIMIT_PER_MINUTE)


def is_allowed_content_type(content_type_header: str | None) -> bool:
    if not content_type_header:
        return True
    mime = content_type_header.split(";")[0].strip().lower()
    return mime in _ALLOWED_CONTENT_TYPES or mime.startswith("text/")


def allowed_proxy_url(url: str | None) -> bool:
    if not url:
        return False
    try:
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https"):
            return False
        if not parsed.hostname:
            return False
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        return port in (80, 443)
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
        raise PermissionError("Host or URL scheme/port not allowed")
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


def default_referer_for_url(url: str) -> str:
    try:
        parsed = urlparse(url)
        if parsed.scheme and parsed.netloc:
            return f"{parsed.scheme}://{parsed.netloc}/"
    except Exception:
        pass
    return ""


def build_proxy_forward_headers(
    url: str,
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
        headers["Accept"] = incoming_headers.get(
            "accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8",
        )
        headers["Accept-Language"] = incoming_headers.get("accept-language", "ko-KR,ko;q=0.9,en;q=0.8")

    referer = (
        (referer_param or "").strip()
        or (incoming_headers.get("referer") or "").strip()
        or default_referer_for_url(url)
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


async def handle_proxy_request(request: Request, user_id: str | None = None, is_head: bool = False):
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
            content={"error": "Forbidden", "message": "Host or URL scheme/port not allowed"},
        )

    # Rate limiting
    if user_id:
        rate_limiter = getattr(request.app.state, "proxy_rate_limiter", None)
        if rate_limiter is not None and not rate_limiter.acquire(user_id):
            if is_head:
                return RawResponse(status_code=429, headers={"Retry-After": "30"})
            return JSONResponse(
                status_code=429,
                headers={"Retry-After": "30"},
                content={"error": "TooManyRequests", "message": "Proxy rate limit exceeded"},
            )

    incoming_headers = {k.lower(): v for k, v in request.headers.items()}
    req_headers = build_proxy_forward_headers(
        url=url_param,
        incoming_headers=incoming_headers,
        referer_param=referer_param,
        include_accept=not is_head,
    )

    current_url = url_param
    method = "HEAD" if is_head else "GET"

    client: httpx.AsyncClient = getattr(request.app.state, "proxy_client", None)
    own_client = False
    if client is None:
        client = create_proxy_client()
        own_client = True

    semaphore: asyncio.Semaphore = getattr(request.app.state, "proxy_semaphore", None)
    if semaphore is None:
        semaphore = create_proxy_semaphore()

    try:
        await asyncio.wait_for(semaphore.acquire(), timeout=0.1)
    except TimeoutError:
        if own_client:
            await client.aclose()
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
                    if own_client:
                        await client.aclose()
                    return RawResponse(status_code=502) if is_head else JSONResponse(
                        status_code=502,
                        content={"error": "BadGateway", "message": "Too many redirects"},
                    )
                location = resp.headers.get("Location")
                nxt = next_proxy_url(current_url, location)
                await resp.aclose()
                if nxt is None:
                    release_semaphore()
                    if own_client:
                        await client.aclose()
                    if is_head:
                        return RawResponse(status_code=403)
                    return JSONResponse(status_code=403, content={"error": "Forbidden", "message": "Redirect target not allowed"})
                if resp.status_code == 303:
                    method = "GET"
                current_url = nxt
                continue
            break

        if resp is None:
            release_semaphore()
            if own_client:
                await client.aclose()
            if is_head:
                return RawResponse(status_code=502)
            return JSONResponse(status_code=502, content={"error": "BadGateway", "message": "Proxy request failed"})

        # Content-Type validation: only text / document / json data allowed
        content_type = resp.headers.get("content-type")
        if not is_allowed_content_type(content_type):
            await resp.aclose()
            release_semaphore()
            if own_client:
                await client.aclose()
            if is_head:
                return RawResponse(status_code=415)
            return JSONResponse(
                status_code=415,
                content={
                    "error": "UnsupportedMediaType",
                    "message": f"Content-Type '{content_type}' is not supported by proxy (documents/data only)",
                },
            )

        out_headers = passthrough_proxy_headers(resp.headers)
        out_headers["Access-Control-Allow-Origin"] = "*"

        content_length = resp.headers.get("content-length")
        if content_length and not is_head:
            try:
                if int(content_length) > settings.MAX_PROXY_RESPONSE_BYTES:
                    await resp.aclose()
                    release_semaphore()
                    if own_client:
                        await client.aclose()
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
            if own_client:
                await client.aclose()
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
                if own_client:
                    await client.aclose()

        return StreamingResponse(
            stream_content(),
            status_code=resp.status_code,
            headers=out_headers,
        )

    except asyncio.CancelledError:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        if own_client:
            await client.aclose()
        raise
    except PermissionError as exc:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        if own_client:
            await client.aclose()
        if is_head:
            return RawResponse(status_code=403)
        return JSONResponse(status_code=403, content={"error": "Forbidden", "message": str(exc)})
    except httpx.TimeoutException:
        if resp is not None:
            await resp.aclose()
        release_semaphore()
        if own_client:
            await client.aclose()
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
        if own_client:
            await client.aclose()
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
    identity: SyncIdentity = Depends(get_existing_sync_user),
):
    return await handle_proxy_request(request, user_id=identity.user_id, is_head=False)


@router.head("/proxy")
@router.head("/proxy/proxy")
@router.head("/api/comics8/proxy")
@router.head("/api/comics8/proxy/proxy")
async def proxy_head(
    request: Request,
    identity: SyncIdentity = Depends(get_existing_sync_user),
):
    return await handle_proxy_request(request, user_id=identity.user_id, is_head=True)

