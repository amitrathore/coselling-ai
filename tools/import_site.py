#!/usr/bin/env python3
"""Import public Coselling WordPress content and media for a self-contained build."""

from concurrent.futures import ThreadPoolExecutor, as_completed
from html import unescape
from pathlib import Path
from urllib.parse import urlparse
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
API = "https://coselling.ai/wp-json/wp/v2/pages?per_page=100&_embed"
UPLOAD_RE = re.compile(r"https?://[^\s\"'<>]+/wp-content/uploads/[^\s\"'<>]+", re.I)


def fetch(url: str) -> bytes:
    result = subprocess.run(
        ["curl", "--silent", "--show-error", "--fail", "--location", url],
        check=True,
        capture_output=True,
        timeout=60,
    )
    return result.stdout


def asset_path(url: str) -> Path:
    parsed = urlparse(url)
    marker = "/wp-content/uploads/"
    relative = parsed.path.split(marker, 1)[1]
    return ROOT / "assets" / "images" / relative


def download_asset(url: str) -> tuple[str, str]:
    target = asset_path(url)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or target.stat().st_size == 0:
        target.write_bytes(fetch(url))
    return url, target.relative_to(ROOT).as_posix()


def main() -> None:
    pages = json.loads(fetch(API))
    content_dir = ROOT / "content"
    content_dir.mkdir(exist_ok=True)

    urls: set[str] = set()
    for page in pages:
        rendered = unescape(page["content"]["rendered"])
        urls.update(url.rstrip("),;") for url in UPLOAD_RE.findall(rendered))

    # Include assets referenced by the global header/footer and original homepage.
    homepage = fetch("https://coselling.ai/").decode("utf-8", "ignore")
    urls.update(url.rstrip("),;") for url in UPLOAD_RE.findall(unescape(homepage)))

    mapping: dict[str, str] = {}
    failures: list[tuple[str, str]] = []
    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = {pool.submit(download_asset, url): url for url in sorted(urls)}
        for future in as_completed(futures):
            try:
                url, local = future.result()
                mapping[url] = local
            except Exception as exc:  # Continue so a single stale thumbnail cannot block the import.
                failures.append((futures[future], str(exc)))

    exported = []
    for page in pages:
        exported.append(
            {
                "id": page["id"],
                "slug": page["slug"],
                "title": unescape(page["title"]["rendered"]),
                "link": page["link"],
                "content": page["content"]["rendered"],
            }
        )

    (content_dir / "wordpress-pages.json").write_text(
        json.dumps(exported, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (content_dir / "asset-manifest.json").write_text(
        json.dumps(mapping, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"Imported {len(exported)} pages and {len(mapping)} assets.")
    if failures:
        print(f"Skipped {len(failures)} unavailable assets:")
        for url, error in failures:
            print(f"  {url}: {error}")


if __name__ == "__main__":
    main()
