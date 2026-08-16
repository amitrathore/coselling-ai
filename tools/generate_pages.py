#!/usr/bin/env python3
"""Generate dependency-free secondary pages from captured WordPress content."""

from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
from difflib import SequenceMatcher
import json
import re

ROOT = Path(__file__).resolve().parents[1]
CONTENT_FILES = ("wordpress-pages.json", "wordpress-posts.json", "wordpress-integrations.json")
DROP = {"script", "style", "iframe", "form", "input", "button", "noscript", "svg", "path"}
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
KEEP = {"h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "blockquote", "strong", "em", "b", "i", "small", "br", "hr", "a", "img", "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td"}
INTERNAL_HOSTS = {"", "coselling.ai", "www.coselling.ai", "amitr145.sg-host.com"}
POLICY_ROUTES = {
    "privacy-policy": "privacy-policy",
    "terms-of-service": "terms-of-use",
}


def route_path(link: str) -> str:
    return urlparse(link).path.strip("/")


def root_prefix(route: str) -> str:
    return "../" * (len(Path(route).parts) + 1)


def local_asset(value: str, manifest: dict[str, str], prefix: str) -> str:
    value = unescape(value)
    if value.startswith("/"):
        value = "https://coselling.ai" + value
    clean = value.split("?", 1)[0]
    aliases = [value, clean]
    if "amitr145.sg-host.com" in clean:
        aliases.append(clean.replace("amitr145.sg-host.com", "coselling.ai"))
    if "Mask-group.png" in clean:
        aliases.append(clean.replace("Mask-group.png", "Mask-Group.png"))
    lowered = {key.split("?", 1)[0].lower(): local for key, local in manifest.items()}
    for alias in aliases:
        local = manifest.get(alias) or lowered.get(alias.split("?", 1)[0].lower())
        if local:
            return prefix + local
    return value


def local_link(value: str, prefix: str) -> str:
    if value.startswith(("#", "mailto:", "tel:")):
        return value
    value = unescape(value)
    parsed = urlparse(value)
    if value.startswith("/") or parsed.netloc in INTERNAL_HOSTS:
        path = parsed.path.strip("/")
        if not path:
            target = prefix
        else:
            parts = path.split("/")
            if len(parts) >= 3 and parts[:2] == ["contactus", "policies"]:
                mapped = POLICY_ROUTES.get(parts[-1], "contactus")
                target = f"{prefix}pages/{mapped}/"
            elif parts[0] == "support":
                target = f"{prefix}pages/contactus/"
            elif path == "solutions/integrations":
                target = f"{prefix}pages/integrations/"
            else:
                target = f"{prefix}pages/{path}/"
        return target + (f"#{parsed.fragment}" if parsed.fragment else "")
    return value


class Cleaner(HTMLParser):
    def __init__(self, manifest: dict[str, str], prefix: str):
        super().__init__(convert_charrefs=False)
        self.manifest = manifest
        self.prefix = prefix
        self.out: list[str] = []
        self.skip_depth = 0
        self.images: set[str] = set()

    def handle_starttag(self, tag, attrs):
        if self.skip_depth:
            if tag not in VOID:
                self.skip_depth += 1
            return
        attrs_dict = dict(attrs)
        if tag in DROP:
            self.skip_depth = 1
            return
        if tag not in KEEP:
            if attrs_dict.get("id"):
                self.out.append(f'<span id="{escape(attrs_dict["id"], quote=True)}"></span>')
            return
        safe = []
        if tag == "a" and attrs_dict.get("href"):
            href = local_link(attrs_dict["href"], self.prefix)
            safe.append(("href", href))
            if urlparse(href).netloc:
                safe += [("rel", "noopener")]
        if tag == "img":
            src = attrs_dict.get("src") or attrs_dict.get("data-src") or ""
            src = local_asset(src, self.manifest, self.prefix)
            if not src or src.startswith(("http://", "https://", "//")) or src in self.images:
                return
            self.images.add(src)
            safe += [("src", src), ("alt", attrs_dict.get("alt", "")), ("loading", "lazy")]
        if attrs_dict.get("id"):
            safe.append(("id", attrs_dict["id"]))
        rendered = "".join(f' {key}="{escape(value, quote=True)}"' for key, value in safe)
        self.out.append(f"<{tag}{rendered}>")

    def handle_startendtag(self, tag, attrs):
        if self.skip_depth:
            return
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if self.skip_depth:
            self.skip_depth -= 1
            return
        if tag in KEEP and tag not in {"img", "br", "hr"}:
            self.out.append(f"</{tag}>")

    def handle_data(self, data):
        if not self.skip_depth:
            self.out.append(data)

    def handle_entityref(self, name):
        if not self.skip_depth:
            self.out.append(f"&{name};")

    def handle_charref(self, name):
        if not self.skip_depth:
            self.out.append(f"&#{name};")


def clean_content(raw: str, title: str, manifest: dict[str, str], prefix: str) -> str:
    cleaner = Cleaner(manifest, prefix)
    cleaner.feed(raw)
    content = "".join(cleaner.out).replace("\r\n", "\n").replace("\r", "\n")
    content = re.sub(r"<(p|h[1-6]|li|blockquote)>\s*</\1>", "", content)
    content = re.sub(r"\n\s*\n+", "\n", content)
    # The redesigned hero already carries the page title and replaces this breadcrumb.
    content = re.sub(
        r"<h([1-3])>\s*<a[^>]*>Home</a>\s*/.*?</h\1>",
        "",
        content,
        flags=re.I | re.S,
    )
    content = re.sub(
        r"^\s*<h[1-3]>\s*" + re.escape(title) + r"\s*</h[1-3]>",
        "",
        content,
        count=1,
        flags=re.I,
    )
    content = dedupe_blocks(
        content,
        near=title.lower() in {"brands", "creators", "communities", "publishers", "web 2.5"},
    )
    content = re.sub(r"[ \t]+\n", "\n", content)
    content = re.sub(r"\n\s*\n+", "\n", content)
    return content.strip()


def dedupe_blocks(content: str, near: bool = False) -> str:
    """Collapse exact responsive-builder copies while preserving every unique phrase."""
    seen: set[tuple[str, str]] = set()
    prior: dict[str, list[str]] = {}
    pattern = re.compile(r"<(h[1-6]|p|li|a)(?:\s[^>]*)?>.*?</\1>", re.I | re.S)

    def keep_once(match: re.Match[str]) -> str:
        tag = match.group(1).lower()
        text = re.sub(r"<[^>]+>", " ", match.group(0))
        text = " ".join(unescape(text).lower().split())
        key = (tag, text)
        if text and key in seen:
            return ""
        if near and len(text) >= 24:
            threshold = .82 if tag.startswith("h") else .92
            if any(SequenceMatcher(None, text, other).ratio() >= threshold for other in prior.get(tag, [])):
                # Keep alternate responsive wording in the document for source
                # parity without rendering a duplicate visual section.
                return re.sub(rf"^<{tag}", f"<{tag} hidden", match.group(0), count=1, flags=re.I)
        seen.add(key)
        prior.setdefault(tag, []).append(text)
        return match.group(0)

    return pattern.sub(keep_once, content)


def navigation(prefix: str) -> str:
    links = "".join(
        f'<a href="{prefix}pages/{slug}/">{label}</a>'
        for slug, label in (("brands", "Brands"), ("creators", "Creators"), ("communities", "Communities"), ("publishers", "Publishers"), ("solutions", "Solutions"), ("blog", "Blog"))
    )
    return f'<nav id="site-nav" aria-label="Main navigation">{links}</nav>'


def page_html(title: str, content: str, route: str, kind: str = "") -> str:
    prefix = root_prefix(route)
    legal = " legal-content" if route in {"privacy-policy", "terms-of-use", "membership-agreement", "cookie-policy", "awake-market-membership-agreement-exhibit-a"} else ""
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Community Commerce for the Age of AI"><title>{escape(title)} - Coselling.ai</title><link rel="icon" href="{prefix}assets/images/2022/01/cropped-Coselling.ai-Logo-32x32.png"><link rel="stylesheet" href="{prefix}styles.css?v=11"></head>
<body class="inner-page"><a class="skip-link" href="#content">Skip to content</a><header class="site-header"><a class="wordmark" href="{prefix}" aria-label="Coselling.ai home"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><button class="nav-toggle" type="button" aria-expanded="false" aria-controls="site-nav"><span></span><span></span><span></span><b class="sr-only">Menu</b></button>{navigation(prefix)}<a class="header-cta" href="{prefix}pages/contactus/">Join Us <span>↗</span></a></header>
<main id="content"><header class="inner-hero"><p class="eyebrow">The Network is the Market</p><h1>{escape(title)}</h1></header><article class="legacy-content{legal}">{content}</article></main>
<footer><div class="footer-top"><a class="wordmark wordmark--light" href="{prefix}"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><p>FOLLOW US</p><a href="https://www.linkedin.com/company/coselling-ai/" rel="noopener">LinkedIn ↗</a></div><div class="footer-grid"><div><a href="mailto:info@coselling.ai.com">info@coselling.ai.com</a></div><div><a href="{prefix}pages/brands/">Brands</a><a href="{prefix}pages/creators/">Creators</a><a href="{prefix}pages/communities/">Communities</a><a href="{prefix}pages/publishers/">Publishers</a></div><div><a href="{prefix}pages/solutions/">Solutions</a><a href="{prefix}pages/blog/">Blog</a><a href="{prefix}pages/about-us/">About Us</a><a href="{prefix}pages/contactus/">Contact Us</a></div><div><a href="{prefix}pages/privacy-policy/">Privacy Policy</a><a href="{prefix}pages/terms-of-use/">Terms of Use</a><a href="{prefix}pages/membership-agreement/">Membership Agreement</a><a href="{prefix}pages/sitemap/">Sitemap</a></div></div><p class="copyright">© coselling.ai 2026. All rights reserved.</p></footer><script src="{prefix}script.js"></script></body></html>"""


SOLUTION_CONTENT = """<h2>Do more with coselling.ai</h2><p>Harness the power of coselling.ai to increase revenue.</p><h3>Multi-Touch Attribution</h3><p>First party sales network where everyone in the sales funnel from introduction to close will get paid</p><h3>Universal Shopping Catalog</h3><p>One click import of product catalogs from all popular ecommerce platforms</p><h3>Universal Payments and Wallets</h3><p>Multi-currency payment and payout infrastructure using coselling.ai wallet</p><h3>Headless Ecommerce</h3><p>Create stunning storefronts with no code / low code environments</p><h3>Multi-Vendor Checkout</h3><p>Checkout products across multiple vendors in a single cart</p>"""
NEWS_CONTENT = """<p>A borderless world economy powered by a secure and fair Internet that works for all. To empower the world’s largest salesforce, a global network of cosellers across all platforms of the Internet.</p><p><a href="https://coselling.ai/blog/">Blog</a></p>"""
CONTACT_FORM = """<form class="contact-form" data-email="info@coselling.ai.com"><div><label for="name">Name</label><input id="name" name="Name" autocomplete="name"></div><div><label for="email">Email</label><input id="email" name="Email" type="email" autocomplete="email" required></div><div><label for="phone">Phone</label><input id="phone" name="Phone" type="tel" autocomplete="tel"></div><div><label for="company">Company</label><input id="company" name="Company" autocomplete="organization"></div><div class="contact-form__wide"><label for="message">If you have a moment, tell us a little bit about your business and where it fits in the creator economy</label><textarea id="message" name="Message" rows="6"></textarea></div><fieldset class="contact-form__wide"><legend>Contact Preferences (Privacy Policy Link Below)</legend><label><input type="checkbox" name="Preferences" value="One-time contact">coselling.ai may contact me one time based on this request</label><label><input type="checkbox" name="Preferences" value="Regular news">coselling.ai may contact me from time to time to share news or relevant items</label><label><input type="checkbox" name="Preferences" value="Partner contact">coselling.ai Partners, Vendors and Cosellers may contact me to join my network or community</label></fieldset><button class="button button-primary" type="submit">Please Contact Me! <span>→</span></button></form>"""
INTEGRATION_DETAIL = """<div class="integration-overview"><p class="eyebrow">Coselling.ai Integration</p><h2>Do more with coselling.ai</h2><p>Harness the power of coselling.ai to increase revenue.</p><a class="button button-primary" href="{href}">Explore all integrations <span>→</span></a></div>"""


def main() -> None:
    manifest = json.loads((ROOT / "content" / "asset-manifest.json").read_text())
    items = []
    for filename in CONTENT_FILES:
        path = ROOT / "content" / filename
        if path.exists():
            items.extend(json.loads(path.read_text()))

    routes: dict[str, dict] = {}
    for item in items:
        route = route_path(item["link"])
        if not route or route == "home":
            continue
        if route not in routes or len(item.get("content", "")) > len(routes[route].get("content", "")):
            routes[route] = item

    # The WordPress index records are intentionally empty; preserve their authored summaries.
    fallbacks = {
        "integrations": {"title": "Integrations", "content": SOLUTION_CONTENT, "link": "https://coselling.ai/integrations/"},
        "news": {"title": "News", "content": NEWS_CONTENT, "link": "https://coselling.ai/news/"},
    }
    for route, fallback in fallbacks.items():
        if route not in routes or not routes[route].get("content", "").strip():
            routes[route] = fallback

    output = ROOT / "pages"
    for route, item in sorted(routes.items()):
        title = item["title"]
        prefix = root_prefix(route)
        content = clean_content(item.get("content", ""), title, manifest, prefix)
        if route.startswith("integrations/") and not content:
            content = INTEGRATION_DETAIL.format(href=f"{prefix}pages/integrations/")
        if route == "contactus":
            content += CONTACT_FORM
        target = output / route / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page_html(title, content, route), encoding="utf-8")
    print(f"Generated {len(routes)} secondary pages.")


if __name__ == "__main__":
    main()
