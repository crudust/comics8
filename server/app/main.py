from __future__ import annotations

import json
import logging
import os
import re
import time
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Request, Response, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response as RawResponse
from app.billing import router as billing_router
from app.config import get_settings
from app.database import close_db, init_db
from app.proxy import (
    create_proxy_client,
    create_proxy_rate_limiter,
    create_proxy_semaphore,
    router as proxy_router,
)
from app.pubsub_worker import start_pubsub_worker, stop_pubsub_worker
from app.sync import router as sync_router

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("comics8.main")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    logger.info("Starting Comics8 Backend Server...")
    try:
        await init_db()
    except Exception as e:
        logger.error(f"Database initialization warning: {e}")

    try:
        start_pubsub_worker()
    except Exception as e:
        logger.warning(f"Pub/Sub worker startup warning: {e}")

    app.state.proxy_client = create_proxy_client()
    app.state.proxy_semaphore = create_proxy_semaphore()
    app.state.proxy_rate_limiter = create_proxy_rate_limiter()

    try:
        yield
    finally:
        logger.info("Shutting down Comics8 Backend Server...")
        try:
            stop_pubsub_worker()
        except Exception as e:
            logger.warning(f"Error stopping Pub/Sub worker: {e}")

        try:
            await app.state.proxy_client.aclose()
        except Exception as e:
            logger.warning(f"Error closing proxy client: {e}")

        try:
            await close_db()
        except Exception as e:
            logger.warning(f"Error closing DB: {e}")


app = FastAPI(
    title="Comics8 Multi-User Sync & Billing Server",
    version="2.0.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

# CORS Configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS", "HEAD", "PUT", "DELETE"],
    allow_headers=["*"],
    expose_headers=[
        "X-Comics8-Sync-Schema",
        "Content-Range",
        "Content-Length",
        "Content-Disposition",
        "Content-Type",
    ],
)


# Sync schema middleware
@app.middleware("http")
async def add_sync_schema_header(request: Request, call_next):
    settings = get_settings()
    response: Response = await call_next(request)
    response.headers[settings.SYNC_SCHEMA_HEADER] = str(settings.SYNC_SCHEMA_CURRENT)
    return response


# Root redirect to GitHub project page
@app.get("/", include_in_schema=False)
@app.head("/", include_in_schema=False)
async def root_redirect():
    return RedirectResponse(url="https://github.com/crudust/comics8", status_code=307)


# Include Routers
app.include_router(sync_router)
app.include_router(proxy_router)
app.include_router(billing_router)


# =============================================================================
# Health Check Endpoints
# =============================================================================

@app.get("/health")
@app.get("/api/comics8/health")
async def health_check():
    settings = get_settings()
    return {
        "status": "ok",
        "service": "comics8-sync",
        "time": int(time.time() * 1000),
        "syncSchema": settings.SYNC_SCHEMA_CURRENT,
    }


@app.head("/health")
@app.head("/api/comics8/health")
async def health_check_head():
    return RawResponse(status_code=200, media_type="application/json")


# =============================================================================
# Version Check Endpoints
# =============================================================================

@app.get("/version")
@app.get("/api/comics8/version")
async def version_check():
    settings = get_settings()
    vfile = settings.version_file
    if os.path.isfile(vfile):
        try:
            with open(vfile, "r", encoding="utf-8") as f:
                data = json.load(f)
            return JSONResponse(status_code=200, content=data)
        except Exception as e:
            logger.error(f"Error reading {vfile}: {e}")

    default_version = {
        "android": {
            "versionCode": 1,
            "versionName": "1.0.0",
            "downloadUrl": "/api/comics8/download/comics8-latest.apk",
            "releaseNotes": "Initial release",
        },
        "desktop": {
            "versionCode": 1,
            "versionName": "1.0.0",
            "downloadUrl": "/api/comics8/download/Comics8-mac.zip",
            "releaseNotes": "Initial release",
            "windowsDownloadUrl": "/api/comics8/download/Comics8-win.zip",
        },
    }
    return JSONResponse(status_code=200, content=default_version)


@app.head("/version")
@app.head("/api/comics8/version")
async def version_check_head():
    return RawResponse(status_code=200, media_type="application/json")


# =============================================================================
# File Download Endpoints
# =============================================================================

def get_file_mime_type(filename: str) -> str:
    if filename.endswith(".apk"):
        return "application/vnd.android.package-archive"
    elif filename.endswith(".zip"):
        return "application/zip"
    elif filename.endswith(".dmg"):
        return "application/x-apple-diskimage"
    return "application/octet-stream"


def resolve_safe_download_path(downloads_dir: str, filename: str) -> str | None:
    clean_filename = os.path.basename(re.sub(r"[^a-zA-Z0-9_\-\.]", "", filename))
    if not clean_filename or clean_filename.startswith("."):
        return None
    root = os.path.realpath(downloads_dir)
    target = os.path.realpath(os.path.join(root, clean_filename))
    if os.path.commonpath([root, target]) != root:
        return None
    if not os.path.isfile(target):
        return None
    return target


@app.get("/download/{filename}")
@app.get("/api/comics8/download/{filename}")
async def download_file(filename: str):
    settings = get_settings()
    file_path = resolve_safe_download_path(settings.downloads_dir, filename)

    if not file_path:
        clean_filename = os.path.basename(re.sub(r"[^a-zA-Z0-9_\-\.]", "", filename))
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"error": "Not Found", "message": f"File {clean_filename} not found"},
        )

    clean_filename = os.path.basename(file_path)
    mime_type = get_file_mime_type(clean_filename)
    return FileResponse(
        path=file_path,
        media_type=mime_type,
        filename=clean_filename,
    )


@app.head("/download/{filename}")
@app.head("/api/comics8/download/{filename}")
async def download_file_head(filename: str):
    settings = get_settings()
    file_path = resolve_safe_download_path(settings.downloads_dir, filename)

    if not file_path:
        return RawResponse(status_code=404)

    file_size = os.path.getsize(file_path)
    clean_filename = os.path.basename(file_path)
    mime_type = get_file_mime_type(clean_filename)
    headers = {
        "Content-Type": mime_type,
        "Content-Length": str(file_size),
        "Content-Disposition": f'attachment; filename="{clean_filename}"',
        "Access-Control-Allow-Origin": "*",
    }
    return RawResponse(status_code=200, headers=headers)
