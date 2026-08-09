#!/usr/bin/env python3
"""Audit current coselling.ai content and images against the static replacement."""

from __future__ import annotations

from collections import Counter
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
import json
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
API = "https://coselling.ai/wp-json/wp/v2"
ENDPOINTS = ("pages", "posts", "integrations")
BOILERPLATE = {
    "home", "brands", "creators", "communities", "publishers", "solutions",
    "blog", "join", "us", "follow", "linkedin", "coselling", "ai",
}
INTENTIONAL_OMISSIONS = {
    "1 844 4shptyp",
    "awake market membership agreement",
    "awake market membersh",
}


class Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.skip = 0
        self.text: list[str] = []
        self.images: set[str] = set()

    def handle_starttag(self, tag: str, attrs) -> None:
        attrs = dict(attrs)
        if tag in {"script", "style", "noscript", "svg"}:
            self.skip += 1
            return
        if self.skip:
            return
        if tag == "img":
            for key in ("src", "data-src"):
                if attrs.get(key):
                    self.images.add(attrs[key].split("?", 1)[0])
            for candidate in attrs.get("srcset", "").split(","):
                value = candidate.strip().split(" ", 1)[0]
                if value:
                    self.images.add(value.split("?", 1)[0])
        for value in attrs.values():
            if isinstance(value, str):
                for image in re.findall(r"url\(['\"]?([^)'\"]+)", value):
                    self.images.add(unescape(image).split("?", 1)[0])

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript", "svg"} and self.skip:
            self.skip -= 1

    def handle_data(self, data: str) -> None:
        if not self.skip:
            value = " ".join(unescape(data).split())
            if value:
                self.text.append(value)


def fetch(endpoint: str) -> list[dict]:
    url = f"{API}/{endpoint}?per_page=100&_fields=slug,title,content,link,modified"
    response = subprocess.run(
        ["curl", "--silent", "--fail", "-L", "--max-time", "60", url],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(response.stdout)


def extract(html: str) -> Extractor:
    parser = Extractor()
    parser.feed(html)
    return parser


def words(text: list[str]) -> Counter[str]:
    tokens = re.findall(r"[a-z0-9]+(?:['’][a-z]+)?", " ".join(text).lower())
    return Counter(token for token in tokens if token not in BOILERPLATE)


def route_for(item: dict, endpoint: str) -> Path:
    path = urlparse(item["link"]).path.strip("/")
    if not path:
        return ROOT / "index.html"
    return ROOT / "pages" / path / "index.html"


def canonical_image(url: str) -> str:
    if url.startswith("/"):
        url = "https://coselling.ai" + url
    return url.replace("https://www.coselling.ai/", "https://coselling.ai/")


def normalized_block(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+(?:['’][a-z]+)?", value.lower()))


def main() -> None:
    manifest = json.loads((ROOT / "content" / "asset-manifest.json").read_text())
    manifest = {canonical_image(remote.split("?", 1)[0]): local for remote, local in manifest.items()}
    remote_items: list[tuple[str, dict]] = []
    for endpoint in ENDPOINTS:
        remote_items.extend((endpoint, item) for item in fetch(endpoint))

    missing_routes = []
    parity = []
    remote_images: set[str] = set()
    for endpoint, item in remote_items:
        source = extract(item["content"]["rendered"])
        remote_images.update(canonical_image(image) for image in source.images)
        target = route_for(item, endpoint)
        if not target.exists():
            missing_routes.append({"type": endpoint, "slug": item["slug"], "path": str(target.relative_to(ROOT))})
            continue
        rendered = extract(target.read_text(errors="replace"))
        rendered_text = normalized_block(" ".join(rendered.text))
        source_blocks = [
            block for block in dict.fromkeys(source.text)
            if normalized_block(block) not in INTENTIONAL_OMISSIONS
        ]
        # WordPress embeds duplicate desktop/mobile copies in the REST payload.
        # Compare unique authored blocks so responsive implementation details do
        # not masquerade as missing content.
        source_words = words(source_blocks)
        rendered_words = words(rendered.text)
        missing = source_words - rendered_words
        total = sum(source_words.values())
        coverage = 100.0 if not total else 100 * (total - sum(missing.values())) / total
        parity.append({
            "type": endpoint,
            "slug": item["slug"],
            "coverage": round(coverage, 1),
            "missing_words": sum(missing.values()),
            "sample": list(missing.elements())[:20],
            "missing_blocks": [
                block for block in source_blocks
                if len(normalized_block(block)) >= 12
                and normalized_block(block) not in rendered_text
            ][:25],
        })

    missing_assets = []
    for remote in sorted(remote_images):
        local = manifest.get(remote)
        if not local or not (ROOT / local).exists():
            missing_assets.append(remote)

    local_references = set()
    for html_file in ROOT.glob("**/*.html"):
        if ".git" in html_file.parts:
            continue
        parsed = extract(html_file.read_text(errors="replace"))
        local_references.update(parsed.images)
    external_media = sorted(ref for ref in local_references if ref.startswith(("http://", "https://", "//")))

    report = {
        "remote_items": len(remote_items),
        "local_html_pages": len(list(ROOT.glob("pages/**/index.html"))) + 1,
        "missing_routes": missing_routes,
        "content_parity": sorted(parity, key=lambda item: (item["coverage"], item["slug"])),
        "remote_images": len(remote_images),
        "missing_assets": missing_assets,
        "external_media_references": external_media,
    }
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
