#!/usr/bin/env python3
import os
import sys
import json
import urllib.request
import urllib.error
import mimetypes

def main():
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    repo = os.environ.get("GITHUB_REPO", "crudust/comics8").strip()
    tag = os.environ.get("RELEASE_TAG", "").strip()
    title = os.environ.get("RELEASE_TITLE", tag).strip()
    notes = os.environ.get("RELEASE_NOTES", "").strip()
    files = sys.argv[1:]

    if not token:
        print("ℹ️  GITHUB_TOKEN is not set; skipping GitHub Releases publication.")
        return

    if not tag:
        print("❌ RELEASE_TAG is required for GitHub release.")
        return

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Comics8-Deployer",
    }

    # 1. Create release (or fetch existing if tag exists)
    release_url = f"https://api.github.com/repos/{repo}/releases"
    payload = json.dumps({
        "tag_name": tag,
        "name": title,
        "body": notes,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")

    req = urllib.request.Request(release_url, data=payload, headers=headers, method="POST")
    release_data = None
    try:
        with urllib.request.urlopen(req) as resp:
            release_data = json.loads(resp.read().decode("utf-8"))
            print(f"🎉 Created GitHub Release {tag} for {repo}")
    except urllib.error.HTTPError as e:
        if e.code == 422:
            get_req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", headers=headers)
            try:
                with urllib.request.urlopen(get_req) as resp:
                    release_data = json.loads(resp.read().decode("utf-8"))
                    print(f"ℹ️  Found existing GitHub Release {tag} for {repo}")
            except Exception as ex:
                print(f"❌ Failed to fetch existing release: {ex}")
                return
        else:
            print(f"❌ GitHub release creation error ({e.code}): {e.read().decode('utf-8')}")
            return

    if not release_data:
        return

    upload_url_template = release_data["upload_url"].split("{")[0]
    existing_assets = {a["name"]: a["id"] for a in release_data.get("assets", [])}

    # 2. Upload file assets
    for filepath in files:
        if not os.path.isfile(filepath):
            continue
        filename = os.path.basename(filepath)
        
        # Delete existing asset if it has the same name
        if filename in existing_assets:
            del_id = existing_assets[filename]
            del_req = urllib.request.Request(
                f"https://api.github.com/repos/{repo}/releases/assets/{del_id}",
                headers=headers,
                method="DELETE",
            )
            try:
                with urllib.request.urlopen(del_req):
                    pass
            except Exception:
                pass

        content_type = mimetypes.guess_type(filepath)[0] or "application/octet-stream"
        upload_url = f"{upload_url_template}?name={filename}"
        print(f"  ⬆️  Uploading {filename} to GitHub Releases...")

        with open(filepath, "rb") as f:
            data = f.read()

        up_headers = dict(headers)
        up_headers["Content-Type"] = content_type
        up_headers["Content-Length"] = str(len(data))

        up_req = urllib.request.Request(upload_url, data=data, headers=up_headers, method="POST")
        try:
            with urllib.request.urlopen(up_req):
                print(f"  ✅ Uploaded {filename} successfully")
        except urllib.error.HTTPError as e:
            print(f"  ⚠️  Upload failed for {filename}: {e.read().decode('utf-8')}")

if __name__ == "__main__":
    main()
