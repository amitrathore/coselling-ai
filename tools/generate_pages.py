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
    route_class = f" {route}-content" if route in {"brands", "creators"} else ""
    style_version = {"brands": 13, "creators": 14, "contactus": 12}.get(route, 11)
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Community Commerce for the Age of AI"><title>{escape(title)} - Coselling.ai</title><link rel="icon" href="{prefix}assets/images/2022/01/cropped-Coselling.ai-Logo-32x32.png"><link rel="stylesheet" href="{prefix}styles.css?v={style_version}"></head>
<body class="inner-page"><a class="skip-link" href="#content">Skip to content</a><header class="site-header"><a class="wordmark" href="{prefix}" aria-label="Coselling.ai home"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><button class="nav-toggle" type="button" aria-expanded="false" aria-controls="site-nav"><span></span><span></span><span></span><b class="sr-only">Menu</b></button>{navigation(prefix)}<a class="header-cta" href="{prefix}pages/contactus/">Join Us <span>↗</span></a></header>
<main id="content"><header class="inner-hero"><p class="eyebrow">The Network is the Market</p><h1>{escape(title)}</h1></header><article class="legacy-content{legal}{route_class}">{content}</article></main>
<footer><div class="footer-top"><a class="wordmark wordmark--light" href="{prefix}"><span class="sunmark">✺</span><span>COSELLING</span><small>.AI</small></a><p>FOLLOW US</p><a href="https://www.linkedin.com/company/coselling-ai/" rel="noopener">LinkedIn ↗</a></div><div class="footer-grid"><div><a href="mailto:info@coselling.ai.com">info@coselling.ai.com</a></div><div><a href="{prefix}pages/brands/">Brands</a><a href="{prefix}pages/creators/">Creators</a><a href="{prefix}pages/communities/">Communities</a><a href="{prefix}pages/publishers/">Publishers</a></div><div><a href="{prefix}pages/solutions/">Solutions</a><a href="{prefix}pages/blog/">Blog</a><a href="{prefix}pages/about-us/">About Us</a><a href="{prefix}pages/contactus/">Contact Us</a></div><div><a href="{prefix}pages/privacy-policy/">Privacy Policy</a><a href="{prefix}pages/terms-of-use/">Terms of Use</a><a href="{prefix}pages/membership-agreement/">Membership Agreement</a><a href="{prefix}pages/sitemap/">Sitemap</a></div></div><p class="copyright">© coselling.ai 2026. All rights reserved.</p></footer><script src="{prefix}script.js"></script></body></html>"""


SOLUTION_CONTENT = """<h2>Do more with coselling.ai</h2><p>Harness the power of coselling.ai to increase revenue.</p><h3>Multi-Touch Attribution</h3><p>First party sales network where everyone in the sales funnel from introduction to close will get paid</p><h3>Universal Shopping Catalog</h3><p>One click import of product catalogs from all popular ecommerce platforms</p><h3>Universal Payments and Wallets</h3><p>Multi-currency payment and payout infrastructure using coselling.ai wallet</p><h3>Headless Ecommerce</h3><p>Create stunning storefronts with no code / low code environments</p><h3>Multi-Vendor Checkout</h3><p>Checkout products across multiple vendors in a single cart</p>"""
NEWS_CONTENT = """<p>A borderless world economy powered by a secure and fair Internet that works for all. To empower the world’s largest salesforce, a global network of cosellers across all platforms of the Internet.</p><p><a href="https://coselling.ai/blog/">Blog</a></p>"""
BRANDS_CONTENT = """<section class="brand-opening"><div class="brand-opening__copy"><p class="eyebrow">Performance-led distribution</p><h2>Make trust your highest-performing sales channel.</h2><p>Put your products inside the recommendations people already rely on. Coselling.ai connects brands to creators, communities, and publishers, then tracks every contributor who helps make the sale.</p><a class="button button-primary" href="{contact}">Connect and grow <span>→</span></a></div><div class="brand-equation" aria-label="Product multiplied by trust multiplied by attribution creates measurable growth"><div><span>Product</span><b>×</b></div><div><span>Trust</span><b>×</b></div><div><span>Attribution</span><b>=</b></div><strong>Measurable<br>growth</strong><small>Every recommendation connected. Every contributor recognized.</small></div></section><section class="brand-outcomes"><div class="brand-section-head"><p class="eyebrow">A better return on attention</p><h2>Performance,<br>not impressions.</h2><p>Grow through real conversations and pay for outcomes you can trace.</p></div><div class="brand-outcome-grid"><article><span>Pay for results</span><h3>Spend on sales actually made.</h3><p>Move budget toward completed transactions instead of rented reach.</p></article><article><span>Expand distribution</span><h3>Show up wherever trust already lives.</h3><p>Reach buyers through aligned creators, communities, and publishers.</p></article><article><span>Reward the full path</span><h3>Credit everyone who moved the sale forward.</h3><p>Track introductions, recommendations, and conversion across the network.</p></article></div></section><section class="brand-process" id="connect-grow"><div class="brand-section-head"><p class="eyebrow">From catalog to network</p><h2>Connect once.<br>Grow together.</h2><p>A simple operating loop turns your existing catalog into a network-ready sales channel.</p></div><ol><li><span>01</span><div><h3>Connect your catalog</h3><p>Bring products and order data into one shared commerce layer.</p></div></li><li><span>02</span><div><h3>Activate aligned cosellers</h3><p>Give trusted partners the products and context they need to recommend well.</p></div></li><li><span>03</span><div><h3>Attribute and share value</h3><p>Measure the full path to purchase and reward the people who helped create it.</p></div></li></ol></section><section class="brand-capabilities"><div class="brand-capabilities__intro"><p class="eyebrow">Built for the full funnel</p><h2>One commerce layer.<br>Many routes to market.</h2><p>Use the pieces you need now, then add more as your network grows.</p><a href="{solutions}">Explore all solutions ↗</a></div><div class="brand-capability-list"><article><span>Catalog</span><h3>Product management</h3><p>Import, organize, and distribute product information across connected storefronts.</p></article><article><span>Attribution</span><h3>Multi-touch tracking</h3><p>See which people and interactions contributed to each purchase.</p></article><article><span>Checkout</span><h3>Multi-vendor commerce</h3><p>Let customers buy across sellers through one uninterrupted cart.</p></article><article><span>Payments</span><h3>Shared value flows</h3><p>Support secure transactions and payouts across the network.</p></article></div></section><section class="brand-final"><p class="eyebrow">Your next channel is a network</p><h2>Turn trusted conversations into measurable growth.</h2><a class="button button-light" href="{contact}">Join the network <span>→</span></a></section>"""
CREATORS_CONTENT = """<section class="creator-opening"><div class="creator-opening__copy"><p class="eyebrow">Full-funnel earning for creators</p><h2>Earn from the influence you already have.</h2><p>Recommend products you genuinely care about and get recognized across the path to purchase, from the first introduction through engagement and close.</p><a class="button button-primary" href="{contact}">Become a coseller <span>→</span></a></div><div class="creator-ledger" aria-label="Influence is recognized at introduction, engagement, and purchase"><div class="creator-ledger__head"><span>Influence ledger</span><small>Live value path</small></div><ol><li><i></i><div><span>Introduction</span><small>A useful product enters the conversation</small></div><b>Recognized</b></li><li><i></i><div><span>Engagement</span><small>Your audience explores and shares</small></div><b>Recognized</b></li><li><i></i><div><span>Purchase</span><small>The network turns trust into action</small></div><b>Rewarded</b></li></ol><strong>Every touch can carry value.</strong></div></section><section class="creator-belief"><div><p class="eyebrow">#NoSelling</p><h2>No hard sell.<br>No borrowed persona.<br>Just useful sharing.</h2></div><p>Your audience follows you for your point of view. Coselling lets that trust stay intact while making genuine recommendations measurable and monetizable.</p></section><section class="creator-principles"><article><span>Stay authentic</span><h3>Share in your own voice.</h3><p>Choose products that fit your work, interests, and audience instead of chasing every campaign.</p></article><article><span>Earn together</span><h3>Community commerce is a team sport.</h3><p>Value can move across introductions, conversations, and the people who help a decision happen.</p></article><article><span>Keep your reach</span><h3>Your network remains yours.</h3><p>Bring earning into the channels and relationships you have already built.</p></article></section><section class="creator-flow" id="cosell-coselling-ai"><div class="creator-flow__intro"><p class="eyebrow">Start with what you know</p><h2>Choose.<br>Share.<br>Earn.</h2><p>Coselling.ai keeps the operating loop simple so the recommendation stays human.</p></div><ol><li><span>Choose</span><p>Access brands and select products you can stand behind.</p></li><li><span>Share</span><p>Create trackable, shoppable recommendations in your own format.</p></li><li><span>Earn</span><p>Follow attributed activity and receive value when your influence contributes.</p></li></ol></section><section class="creator-toolkit"><div class="creator-toolkit__intro"><p class="eyebrow">The infrastructure stays backstage</p><h2>You create the connection.<br>We track the value.</h2><p>Simple tools support the commerce without getting between you and your audience.</p><a href="{solutions}">Explore creator solutions ↗</a></div><div class="creator-toolkit__list"><article><span>01</span><div><h3>Cosell and attribution</h3><p>Make media trackable and shoppable across the internet.</p></div></article><article><span>02</span><div><h3>Secure earnings</h3><p>Track, manage, and withdraw earnings through one secure experience.</p></div></article><article><span>03</span><div><h3>Flexible profiles</h3><p>Manage distinct creator identities and the value each one generates.</p></div></article></div></section><section class="creator-final"><p class="eyebrow">Your point of view already moves people</p><h2>Let it move value, too.</h2><a class="button button-light" href="{contact}">Join as a coseller <span>→</span></a></section>"""
TALLY_FORM_ID = "D4RKVp"
CONTACT_CONTENT = """<section class="lead-layout" aria-labelledby="lead-heading"><div class="lead-copy"><p class="eyebrow">One network. Many ways in.</p><h2 id="lead-heading">Bring the value.<br>Find the right connection.</h2><p class="lead-intro">Coselling works when every participant brings something useful: a product, trusted reach, a community, distribution, or the infrastructure that connects it all.</p><div class="lead-paths" aria-label="Ways to join"><article><span>01 / SELL</span><h3>Brands &amp; merchants</h3><p>Put products inside trusted recommendations and new distribution networks.</p></article><article><span>02 / RECOMMEND</span><h3>Creators, communities &amp; publishers</h3><p>Turn influence and audience trust into measurable, shared revenue.</p></article><article><span>03 / BUILD</span><h3>Operators &amp; technology partners</h3><p>Launch, connect, or scale the infrastructure behind community commerce.</p></article></div></div><div class="lead-form-shell"><div class="lead-form-head"><p class="eyebrow">Start here</p><h2>Tell us where you fit.</h2><p>About three minutes. We’ll use your answers to route you to the most relevant conversation.</p></div><iframe data-tally-src="https://tally.so/embed/{form_id}?alignLeft=1&amp;hideTitle=1&amp;transparentBackground=1&amp;dynamicHeight=1&amp;source=github_pages&amp;origin_page=contact_us" width="100%" height="2100" scrolling="no" frameborder="0" marginheight="0" marginwidth="0" title="Join the coselling.ai network"></iframe><noscript><p><a href="https://tally.so/r/{form_id}">Open the network interest form</a>.</p></noscript><p class="lead-form-status" role="status" aria-live="polite"></p><p class="lead-privacy">By submitting, you agree that coselling.ai may contact you about this request. Read our <a href="{privacy}">privacy policy</a>.</p></div></section>"""
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
        title = "Join the Network" if route == "contactus" else item["title"]
        prefix = root_prefix(route)
        content = clean_content(item.get("content", ""), title, manifest, prefix)
        if route == "brands":
            content = BRANDS_CONTENT.format(
                contact=f"{prefix}pages/contactus/",
                solutions=f"{prefix}pages/solutions/",
            )
        if route == "creators":
            content = CREATORS_CONTENT.format(
                contact=f"{prefix}pages/contactus/",
                solutions=f"{prefix}pages/solutions/",
            )
        if route.startswith("integrations/") and not content:
            content = INTEGRATION_DETAIL.format(href=f"{prefix}pages/integrations/")
        if route == "contactus":
            content = CONTACT_CONTENT.format(
                form_id=TALLY_FORM_ID,
                privacy=f"{prefix}pages/privacy-policy/",
            )
        target = output / route / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page_html(title, content, route), encoding="utf-8")
    print(f"Generated {len(routes)} secondary pages.")


if __name__ == "__main__":
    main()
