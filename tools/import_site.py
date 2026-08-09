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
API = "https://coselling.ai/wp-json/wp/v2"
ENDPOINTS = ("pages", "posts", "integrations")
KNOWN_REPLACEMENTS = {
    # This staging-host image is no longer served by either legacy host. The
    # redesigned Brands card uses the retained production artwork instead.
    "https://amitr145.sg-host.com/wp-content/uploads/2022/02/image-2891.png":
        "assets/images/2022/02/BrandsHomee.png",
}
MEDIA_RE = re.compile(
    r"(?:https?://[^\s\"'<>\\]+?|/wp-content/uploads/[^\s\"'<>\\]+?)\.(?:avif|gif|jpe?g|png|svg|webp)(?:\?[^\s\"'<>\\]*)?",
    re.I,
)


def remove_retired_phone(content: str) -> str:
    """Keep the retired 1-844 public support number out of refreshed exports."""
    return re.sub(r"(?:tel:)?1?-?844-?4SHPTYP|tel:18444747897|18444747897", "", content, flags=re.I)


def fetch(url: str) -> bytes:
    result = subprocess.run(
        ["curl", "--silent", "--show-error", "--fail", "--location", "--max-time", "60", url],
        check=True,
        capture_output=True,
        timeout=70,
    )
    return result.stdout


def asset_path(url: str) -> Path:
    parsed = urlparse(url)
    marker = "/wp-content/uploads/"
    if marker in parsed.path:
        relative = parsed.path.split(marker, 1)[1]
        return ROOT / "assets" / "images" / relative
    filename = Path(parsed.path).name or "asset"
    return ROOT / "assets" / "images" / "external" / parsed.netloc / filename


def download_asset(url: str) -> tuple[str, str]:
    if url.startswith("/"):
        url = "https://coselling.ai" + url
    target = asset_path(url)
    target.parent.mkdir(parents=True, exist_ok=True)
    candidates = [url]
    if "amitr145.sg-host.com" in url:
        candidates.append(url.replace("amitr145.sg-host.com", "coselling.ai"))
    if "Mask-group.png" in url:
        candidates.append(url.replace("Mask-group.png", "Mask-Group.png"))
    if not target.exists() or target.stat().st_size == 0:
        error = None
        for candidate in dict.fromkeys(candidates):
            try:
                target.write_bytes(fetch(candidate))
                break
            except Exception as exc:  # Try the canonical host/case before giving up.
                error = exc
        else:
            raise error or RuntimeError(f"Unable to fetch {url}")
    return url, target.relative_to(ROOT).as_posix()


def extract_media(html: str) -> set[str]:
    return {unescape(url).rstrip("),;") for url in MEDIA_RE.findall(unescape(html))}


def main() -> None:
    content_dir = ROOT / "content"
    content_dir.mkdir(exist_ok=True)
    collections: dict[str, list[dict]] = {}
    for endpoint in ENDPOINTS:
        collections[endpoint] = json.loads(fetch(f"{API}/{endpoint}?per_page=100&_embed"))

    urls: set[str] = set()
    for items in collections.values():
        for item in items:
            urls.update(extract_media(item["content"]["rendered"]))

    # Include assets referenced by the global header/footer and original homepage.
    homepage = fetch("https://coselling.ai/").decode("utf-8", "ignore")
    urls.update(extract_media(homepage))

    manifest_path = content_dir / "asset-manifest.json"
    mapping: dict[str, str] = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    failures: list[tuple[str, str]] = []
    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = {pool.submit(download_asset, url): url for url in sorted(urls)}
        for future in as_completed(futures):
            try:
                url, local = future.result()
                mapping[url] = local
            except Exception as exc:  # One stale source asset must not block the import.
                failures.append((futures[future], str(exc)))

    mapping.update(KNOWN_REPLACEMENTS)

    for endpoint, items in collections.items():
        exported = [
            {
                "id": item["id"],
                "slug": item["slug"],
                "title": unescape(item["title"]["rendered"]),
                "link": item["link"],
                "content": remove_retired_phone(item["content"]["rendered"]),
            }
            for item in items
        ]
        (content_dir / f"wordpress-{endpoint}.json").write_text(
            json.dumps(exported, indent=2, ensure_ascii=False), encoding="utf-8"
        )

    manifest_path.write_text(json.dumps(mapping, indent=2, ensure_ascii=False), encoding="utf-8")
    print(
        "Imported "
        + ", ".join(f"{len(items)} {endpoint}" for endpoint, items in collections.items())
        + f" and {len(mapping)} assets."
    )
    if failures:
        print(f"Skipped {len(failures)} unavailable source assets:")
        for url, error in failures:
            print(f"  {url}: {error}")


if __name__ == "__main__":
    main()
