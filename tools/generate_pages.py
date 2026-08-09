#!/usr/bin/env python3
"""Generate dependency-free secondary pages from the captured WordPress export."""

from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SKIP_SLUGS = {"home", "news", "integrations"}
DROP = {"script", "style", "iframe", "form", "input", "button", "noscript", "svg", "path"}
KEEP = {"h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "blockquote", "strong", "em", "b", "i", "small", "br", "hr", "a", "img", "figure", "figcaption", "table", "thead", "tbody", "tr", "th", "td"}


def local_asset(value: str, manifest: dict[str, str]) -> str:
    value = unescape(value)
    if value.startswith("/"):
        value = "https://coselling.ai" + value
    if value in manifest:
        return "../../" + manifest[value]
    # Resolve size variants or query strings captured under a canonical URL.
    clean = value.split("?", 1)[0]
    for remote, local in manifest.items():
        if remote.split("?", 1)[0] == clean:
            return "../../" + local
    return value


def local_link(value: str) -> str:
    if value.startswith("#") or value.startswith("mailto:") or value.startswith("tel:"):
        return value
    value = unescape(value)
    parsed = urlparse(value)
    if value.startswith("/") or parsed.netloc in {"coselling.ai", "www.coselling.ai"}:
        path = parsed.path.strip("/")
        if not path:
            return "../../" + (f"#{parsed.fragment}" if parsed.fragment else "")
        slug = path.split("/")[-1]
        return f"../{slug}/" + (f"#{parsed.fragment}" if parsed.fragment else "")
    return value


class Cleaner(HTMLParser):
    def __init__(self, manifest: dict[str, str]):
        super().__init__(convert_charrefs=False)
        self.manifest = manifest
        self.out: list[str] = []
        self.depth = 0
        self.images: set[str] = set()

    def handle_starttag(self, tag, attrs):
        if tag in DROP:
            self.depth += 1
            return
        if self.depth:
            return
        attrs = dict(attrs)
        if tag not in KEEP:
            if attrs.get("id"):
                self.out.append(f'<span id="{escape(attrs["id"], quote=True)}"></span>')
            return
        safe = []
        if tag == "a" and attrs.get("href"):
            safe.append(("href", local_link(attrs["href"])))
            if urlparse(attrs["href"]).netloc not in {"", "coselling.ai", "www.coselling.ai"}:
                safe += [("rel", "noopener")]
        if tag == "img":
            src = attrs.get("src") or attrs.get("data-src") or ""
            src = local_asset(src, self.manifest)
            if not src or src.startswith("http") or src in self.images:
                return
            self.images.add(src)
            safe += [("src", src), ("alt", attrs.get("alt", "")), ("loading", "lazy")]
        if attrs.get("id"):
            safe.append(("id", attrs["id"]))
        rendered = "".join(f' {key}="{escape(value, quote=True)}"' for key, value in safe)
        self.out.append(f"<{tag}{rendered}>")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        if tag in DROP:
            self.depth = max(0, self.depth - 1)
            return
        if not self.depth and tag in KEEP and tag not in {"img", "br", "hr"}:
            self.out.append(f"</{tag}>")

    def handle_data(self, data):
        if not self.depth:
            self.out.append(data)

    def handle_entityref(self, name):
        if not self.depth:
            self.out.append(f"&{name};")

    def handle_charref(self, name):
        if not self.depth:
            self.out.append(f"&#{name};")


NAV = """<nav id="site-nav" aria-label="Main navigation"><a href="../brands/">Brands</a><a href="../creators/">Creators</a><a href="../communities/">Communities</a><a href="../publishers/">Publishers</a><a href="../solutions/">Solutions</a><a href="../blog/">Blog</a></nav>"""


def page_html(title: str, content: str) -> str:
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Community Commerce for the Age of AI"><title>{escape(title)} - Coselling.ai</title><link rel="icon" href="../../assets/images/2022/01/cropped-Coselling.ai-Logo-32x32.png"><link rel="stylesheet" href="../../styles.css?v=7"></head>
<body class="inner-page"><a class="skip-link" href="#content">Skip to content</a><header class="site-header"><a class="wordmark" href="../../" aria-label="Coselling.ai home"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><button class="nav-toggle" type="button" aria-expanded="false" aria-controls="site-nav"><span></span><span></span><span></span><b class="sr-only">Menu</b></button>{NAV}<a class="header-cta" href="../contactus/">Join Us <span>↗</span></a></header>
<main id="content"><header class="inner-hero"><p class="eyebrow">The Network is the Market</p><h1>{escape(title)}</h1></header><article class="legacy-content">{content}</article></main>
<footer><div class="footer-top"><a class="wordmark wordmark--light" href="../../"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><p>FOLLOW US</p><a href="https://www.linkedin.com/company/coselling-ai/" rel="noopener">LinkedIn ↗</a></div><div class="footer-grid"><div><a href="mailto:info@coselling.ai.com">info@coselling.ai.com</a></div><div><a href="../brands/">Brands</a><a href="../creators/">Creators</a><a href="../communities/">Communities</a><a href="../publishers/">Publishers</a></div><div><a href="../solutions/">Solutions</a><a href="../blog/">Blog</a><a href="../about-us/">About Us</a><a href="../contactus/">Contact Us</a></div><div><a href="../privacy-policy/">Privacy Policy</a><a href="../terms-of-use/">Terms of Use</a><a href="../membership-agreement/">Membership Agreement</a><a href="../sitemap/">Sitemap</a></div></div><p class="copyright">© coselling.ai 2026. All rights reserved.</p></footer><script src="../../script.js"></script></body></html>"""


def main() -> None:
    pages = json.loads((ROOT / "content" / "wordpress-pages.json").read_text())
    manifest = json.loads((ROOT / "content" / "asset-manifest.json").read_text())
    output = ROOT / "pages"
    count = 0
    for page in pages:
        if page["slug"] in SKIP_SLUGS:
            continue
        cleaner = Cleaner(manifest)
        cleaner.feed(page["content"])
        content = "".join(cleaner.out)
        # Remove empty semantic elements and excess whitespace from builder markup.
        content = re.sub(r"<(p|h[1-6]|li|blockquote)>\s*</\1>", "", content)
        content = re.sub(r"\n\s*\n+", "\n", content)
        if page["slug"] == "contactus":
            content += """<form class="contact-form" action="mailto:info@coselling.ai.com" method="post" enctype="text/plain"><div><label for="name">Name</label><input id="name" name="Name" autocomplete="name"></div><div><label for="email">Email</label><input id="email" name="Email" type="email" autocomplete="email" required></div><div><label for="phone">Phone</label><input id="phone" name="Phone" type="tel" autocomplete="tel"></div><div><label for="company">Company</label><input id="company" name="Company" autocomplete="organization"></div><div class="contact-form__wide"><label for="message">If you have a moment, tell us a little bit about your business and where it fits in the creator economy </label><textarea id="message" name="Message" rows="6"></textarea></div><fieldset class="contact-form__wide"><legend>Contact Preferences (Privacy Policy Link Below)</legend><label><input type="checkbox" name="Preferences" value="One-time contact">coselling.ai may contact me one time based on this request</label><label><input type="checkbox" name="Preferences" value="Regular news">coselling.ai may contact me from time to time to share news or relevant items</label><label><input type="checkbox" name="Preferences" value="Partner contact">coselling.ai Partners, Vendors and Cosellers may contact me to join my network or community</label></fieldset><button class="button button-primary" type="submit">Send request <span>→</span></button></form>"""
        target = output / page["slug"] / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page_html(page["title"], content), encoding="utf-8")
        count += 1
    solution_content = """<h2>Do more with coselling.ai</h2><p>Harness the power of coselling.ai to increase revenue.</p><h3>Multi-Touch Attribution</h3><p>First party sales network where everyone in the sales funnel from introduction to close will get paid</p><h3>Universal Shopping Catalog</h3><p>One click import of product catalogs from all popular ecommerce platforms</p><h3>Universal Payments and Wallets</h3><p>Multi-currency payment and payout infrastructure using coselling.ai wallet</p><h3>Headless Ecommerce</h3><p>Create stunning storefronts with no code / low code environments</p><h3>Multi-Vendor Checkout</h3><p>Checkout products across multiple vendors in a single cart</p>"""
    (output / "solutions" / "index.html").write_text(page_html("Solutions", solution_content), encoding="utf-8")
    integrations = output / "integrations" / "index.html"
    integrations.parent.mkdir(parents=True, exist_ok=True)
    integrations.write_text(page_html("Integrations", solution_content), encoding="utf-8")
    news = output / "news" / "index.html"
    news.parent.mkdir(parents=True, exist_ok=True)
    news.write_text(page_html("News", '<p>A borderless world economy powered by a secure and fair Internet that works for all. To empower the world’s largest salesforce, a global network of cosellers across all platforms of the Internet.</p><p><a href="../blog/">Blog</a></p>'), encoding="utf-8")
    print(f"Generated {count} secondary pages.")


if __name__ == "__main__":
    main()
