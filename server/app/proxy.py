from __future__ import annotations

import logging
import re
from typing import AsyncGenerator
from urllib.parse import urljoin, urlparse
import httpx
from fastapi import APIRouter, Header, Query, Request, Response
from fastapi.responses import JSONResponse, Response as RawResponse, StreamingResponse
from app.config import get_settings

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


def create_proxy_client() -> httpx.AsyncClient:
    settings = get_settings()
    return httpx.AsyncClient(
        timeout=httpx.Timeout(settings.PROXY_TIMEOUT_SECONDS, connect=10.0),
        follow_redirects=False,
    )


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
        if parsed.scheme not in ("http", "https"):
            return False
        return is_allowed_proxy_host(parsed.hostname)
    except Exception:
        return False


def next_proxy_url(current_url: str, location: str | None) -> str | None:
    if not location or not str(location).strip():
        return None
    nxt = urljoin(current_url, str(location).strip())
    if not allowed_proxy_url(nxt):
        return None
    return nxt


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

    resp: httpx.Response | None = None
    try:
        for _ in range(settings.MAX_PROXY_REDIRECTS + 1):
            if not allowed_proxy_url(current_url):
                if is_head:
                    return RawResponse(status_code=403)
                return JSONResponse(status_code=403, content={"error": "Forbidden", "message": "Host not allowed"})

            req = client.build_request(method, current_url, headers=req_headers)
            resp = await client.send(req, stream=not is_head)

            if resp.status_code in PROXY_REDIRECT_STATUSES:
                location = resp.headers.get("Location")
                nxt = next_proxy_url(current_url, location)
                await resp.aclose()
                if nxt is None:
                    if is_head:
                        return RawResponse(status_code=403)
                    return JSONResponse(status_code=403, content={"error": "Forbidden", "message": "Host not allowed"})
                if resp.status_code == 303:
                    method = "GET"
                current_url = nxt
                continue
            break

        if resp is None:
            if is_head:
                return RawResponse(status_code=502)
            return JSONResponse(status_code=502, content={"error": "BadGateway", "message": "Proxy request failed"})

        out_headers = passthrough_proxy_headers(resp.headers)
        out_headers["Access-Control-Allow-Origin"] = "*"

        if is_head:
            status_code = resp.status_code
            await resp.aclose()
            return RawResponse(status_code=status_code, headers=out_headers)

        async def stream_content() -> AsyncGenerator[bytes, None]:
            try:
                async for chunk in resp.aiter_bytes(chunk_size=65536):
                    yield chunk
            finally:
                await resp.aclose()

        return StreamingResponse(
            stream_content(),
            status_code=resp.status_code,
            headers=out_headers,
        )

    except httpx.TimeoutException:
        if resp is not None:
            await resp.aclose()
        if is_head:
            return RawResponse(status_code=504)
        return JSONResponse(
            status_code=504,
            content={"error": "GatewayTimeout", "message": "Target server timed out"},
        )
    except Exception as e:
        if resp is not None:
            await resp.aclose()
        logger.error(f"Proxy error for {url_param}: {e}")
        if is_head:
            return RawResponse(status_code=502)
        return JSONResponse(
            status_code=502,
            content={"error": "BadGateway", "message": f"Proxy request failed: {e}"},
        )


@router.get("/proxy")
@router.get("/proxy/proxy")
@router.get("/api/comics8/proxy")
@router.get("/api/comics8/proxy/proxy")
async def proxy_get(request: Request):
    return await handle_proxy_request(request, is_head=False)


@router.head("/proxy")
@router.head("/proxy/proxy")
@router.head("/api/comics8/proxy")
@router.head("/api/comics8/proxy/proxy")
async def proxy_head(request: Request):
    return await handle_proxy_request(request, is_head=True)
