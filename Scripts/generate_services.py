#!/usr/bin/env python3
"""Generate the bundled popular-services catalog.

Writes:
  SyncedPass/Models/KnownService+Catalog.swift   (names, domains, colors)
  SyncedPass/Assets.xcassets/Services/*.imageset (template SVG logos)

Logos come from two CC0 (public domain) sets:
  - Simple Icons (https://simpleicons.org): single-color marks, tinted by the app
  - gilbarbara/logos via Iconify (https://github.com/gilbarbara/logos): full-color
    marks, used for multicolor brands (PREFER_COLOR) and brands Simple Icons
    no longer carries (COLOR_LOGOS)
A few services in neither set use their official icon instead, stored in
Scripts/logos/ (see OFFICIAL_LOGOS). Any other brand still gets an entry; the
app shows a letter in the brand color for them. Icons are bundled so the app never asks a server for
logos, which would reveal which sites you have accounts on.

Usage:
  npm pack simple-icons @iconify-json/logos
  mkdir si logos && tar xzf simple-icons-*.tgz -C si && tar xzf iconify-json-logos-*.tgz -C logos
  python3 Scripts/generate_services.py si/package logos/package
"""

import json
import re
import shutil
import sys
from pathlib import Path

# (Simple Icons slug or own id, display name, domains, fallback hex if no logo)
# The first domain is the one filled in when the service is picked.
SERVICES = [
    ("google", "Google", ["google.com"], None),
    ("gmail", "Gmail", ["gmail.com", "mail.google.com"], None),
    ("googledrive", "Google Drive", ["drive.google.com"], None),
    ("youtube", "YouTube", ["youtube.com", "youtu.be"], None),
    ("apple", "Apple", ["apple.com", "appleid.apple.com"], None),
    ("icloud", "iCloud", ["icloud.com"], None),
    ("microsoft", "Microsoft", ["microsoft.com", "live.com", "microsoftonline.com"], "00A4EF"),
    ("outlook", "Outlook", ["outlook.com", "outlook.live.com"], "0078D4"),
    ("proton", "Proton", ["proton.me", "protonmail.com"], None),
    ("yahoo", "Yahoo", ["yahoo.com"], "6001D2"),
    ("facebook", "Facebook", ["facebook.com", "fb.com"], None),
    ("messenger", "Messenger", ["messenger.com"], None),
    ("instagram", "Instagram", ["instagram.com"], None),
    ("whatsapp", "WhatsApp", ["whatsapp.com"], None),
    ("threads", "Threads", ["threads.net", "threads.com"], None),
    ("x", "X (Twitter)", ["x.com", "twitter.com"], None),
    ("linkedin", "LinkedIn", ["linkedin.com"], "0A66C2"),
    ("reddit", "Reddit", ["reddit.com"], None),
    ("discord", "Discord", ["discord.com", "discord.gg"], None),
    ("telegram", "Telegram", ["telegram.org", "t.me"], None),
    ("signal", "Signal", ["signal.org"], None),
    ("tiktok", "TikTok", ["tiktok.com"], None),
    ("snapchat", "Snapchat", ["snapchat.com"], None),
    ("pinterest", "Pinterest", ["pinterest.com"], None),
    ("tumblr", "Tumblr", ["tumblr.com"], None),
    ("quora", "Quora", ["quora.com"], None),
    ("medium", "Medium", ["medium.com"], None),
    ("slack", "Slack", ["slack.com"], "4A154B"),
    ("zoom", "Zoom", ["zoom.us", "zoom.com"], None),
    ("notion", "Notion", ["notion.so", "notion.com"], None),
    ("trello", "Trello", ["trello.com"], None),
    ("atlassian", "Atlassian", ["atlassian.com", "atlassian.net"], None),
    ("figma", "Figma", ["figma.com"], None),
    ("canva", "Canva", ["canva.com"], "00C4CC"),
    ("adobe", "Adobe", ["adobe.com"], "FF0000"),
    ("dropbox", "Dropbox", ["dropbox.com"], None),
    ("netflix", "Netflix", ["netflix.com"], None),
    ("spotify", "Spotify", ["spotify.com"], None),
    ("twitch", "Twitch", ["twitch.tv"], None),
    ("steam", "Steam", ["steampowered.com", "steamcommunity.com"], None),
    ("epicgames", "Epic Games", ["epicgames.com"], None),
    ("playstation", "PlayStation", ["playstation.com"], None),
    ("xbox", "Xbox", ["xbox.com"], "107C10"),
    ("nintendo", "Nintendo", ["nintendo.com"], "E60012"),
    ("battledotnet", "Battle.net", ["battle.net", "blizzard.com"], None),
    ("ea", "EA", ["ea.com"], None),
    ("ubisoft", "Ubisoft", ["ubisoft.com"], None),
    ("roblox", "Roblox", ["roblox.com"], None),
    ("amazon", "Amazon", ["amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr"], "FF9900"),
    ("ebay", "eBay", ["ebay.com"], None),
    ("aliexpress", "AliExpress", ["aliexpress.com"], None),
    ("shopify", "Shopify", ["shopify.com"], None),
    ("airbnb", "Airbnb", ["airbnb.com"], None),
    ("bookingdotcom", "Booking.com", ["booking.com"], None),
    ("uber", "Uber", ["uber.com"], None),
    ("paypal", "PayPal", ["paypal.com"], None),
    ("stripe", "Stripe", ["stripe.com"], None),
    ("revolut", "Revolut", ["revolut.com"], None),
    ("wise", "Wise", ["wise.com"], None),
    ("binance", "Binance", ["binance.com"], None),
    ("coinbase", "Coinbase", ["coinbase.com"], None),
    ("kraken", "Kraken", ["kraken.com"], "5741D9"),
    ("bybit", "Bybit", ["bybit.com"], "F7A600"),
    ("okx", "OKX", ["okx.com"], None),
    ("metamask", "MetaMask", ["metamask.io"], "F6851B"),
    ("openai", "ChatGPT", ["chatgpt.com", "openai.com"], "000000"),
    ("claude", "Claude", ["claude.ai"], None),
    ("github", "GitHub", ["github.com"], None),
    ("gitlab", "GitLab", ["gitlab.com"], None),
    ("bitbucket", "Bitbucket", ["bitbucket.org"], None),
    ("stackoverflow", "Stack Overflow", ["stackoverflow.com"], None),
    ("npm", "npm", ["npmjs.com"], None),
    ("docker", "Docker", ["docker.com"], None),
    ("jetbrains", "JetBrains", ["jetbrains.com"], None),
    ("postman", "Postman", ["postman.com"], None),
    ("cloudflare", "Cloudflare", ["cloudflare.com"], None),
    ("digitalocean", "DigitalOcean", ["digitalocean.com"], None),
    ("vercel", "Vercel", ["vercel.com"], None),
    ("netlify", "Netlify", ["netlify.com"], None),
    ("heroku", "Heroku", ["heroku.com"], "430098"),
    ("firebase", "Firebase", ["firebase.google.com"], None),
    ("supabase", "Supabase", ["supabase.com"], None),
    ("mongodb", "MongoDB", ["mongodb.com"], None),
    ("namecheap", "Namecheap", ["namecheap.com"], None),
    ("godaddy", "GoDaddy", ["godaddy.com"], None),
    ("hostinger", "Hostinger", ["hostinger.com"], None),
    ("hackerone", "HackerOne", ["hackerone.com"], None),
    ("bugcrowd", "Bugcrowd", ["bugcrowd.com"], None),
    ("portswigger", "PortSwigger", ["portswigger.net"], None),
    ("yeswehack", "YesWeHack", ["yeswehack.com"], "E60000"),
    # Algérie Poste: no free logo, so letter tiles in its brand yellow and blue.
    ("baridimob", "BaridiMob", ["baridiweb.poste.dz", "epay.poste.dz"], "FECC0B"),
    ("eccp", "ECCP", ["eccp.poste.dz"], "22297C"),
    ("wikipedia", "Wikipedia", ["wikipedia.org"], None),
    ("duolingo", "Duolingo", ["duolingo.com"], None),
    ("udemy", "Udemy", ["udemy.com"], None),
    ("coursera", "Coursera", ["coursera.org"], None),
    ("patreon", "Patreon", ["patreon.com"], None),
    ("bitwarden", "Bitwarden", ["bitwarden.com"], None),
    ("1password", "1Password", ["1password.com"], None),
    ("lastpass", "LastPass", ["lastpass.com"], None),
    ("easyeda", "EasyEDA", ["easyeda.com"], None),
    ("skillshare", "Skillshare", ["skillshare.com"], None),
    ("sanity", "Sanity", ["sanity.io"], None),
    ("poe", "Poe", ["poe.com"], None),
    ("dailydotdev", "daily.dev", ["daily.dev"], None),
]

# Brands whose real logo is multicolor (or whose Simple Icons version is a
# wordmark that's unreadable in a small tile) use gilbarbara's full-color
# icon even though Simple Icons has one: drawn in a single color they look
# wrong (Gmail's M as a white shape on red). Chosen by comparing both sets
# side by side; wide wordmarks (Yahoo, Stripe, Quora…) and outdated logos
# (Kraken, Patreon) were rejected.
PREFER_COLOR = {
    "google": "google-icon",
    "gmail": "google-gmail",
    "googledrive": "google-drive",
    "youtube": "youtube-icon",
    "messenger": "messenger",
    "telegram": "telegram",
    "tiktok": "tiktok-icon",
    "zoom": "zoom-icon",
    "medium": "medium-icon",
    "figma": "figma",
    "trello": "trello",
    "atlassian": "atlassian",
    "netflix": "netflix-icon",
    "shopify": "shopify",
    "paypal": "paypal",
    "gitlab": "gitlab-icon",
    "bitbucket": "bitbucket",
    "stackoverflow": "stackoverflow-icon",
    "jetbrains": "jetbrains-icon",
    "cloudflare": "cloudflare-icon",
    "netlify": "netlify-icon",
    "firebase": "firebase-icon",
    "supabase": "supabase-icon",
    "namecheap": "namecheap",
}

# Simple Icons only has a wordmark for these, unreadable at tile size; a
# letter in the brand color is clearer.
LETTER_ONLY = {"aliexpress", "coinbase"}

# Official icons for services neither CC0 set has, kept in Scripts/logos/.
# These are the owners' trademarks, bundled only to identify the service:
# - baridimob.png: the BaridiMob App Store icon (published by Algérie Poste),
#   a complete app icon, so it fills the tile ("appIcon")
# - eccp.png: the Algérie Poste emblem from eccp.poste.dz/img/logo.png,
#   cropped to the emblem (the text below is unreadable at icon size)
OFFICIAL_LOGOS = {
    "baridimob": ("baridimob.png", ".appIcon"),
    "eccp": ("eccp.png", ".color"),
}

# Full-color fallbacks from gilbarbara/logos, keyed by service id.
COLOR_LOGOS = {
    "microsoft": "microsoft-icon",
    "linkedin": "linkedin-icon",
    "slack": "slack-icon",
    "adobe": "adobe-icon",
    "metamask": "metamask-icon",
    "openai": "openai-icon",
    "heroku": "heroku-icon",
}

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "SyncedPass" / "Assets.xcassets"
SWIFT_OUT = ROOT / "SyncedPass" / "Models" / "KnownService+Catalog.swift"


_NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
_ARG_COUNTS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}
_SEPARATORS = " ,\t\n"


def expand_path(d: str) -> str:
    """Rewrite path data with every argument separated.

    Simple Icons uses compact arc flags ("a1 1 0 01.5.5"), which Apple's
    CoreSVG can't parse; expanded ("a 1 1 0 0 1 .5 .5") it renders fine.
    """
    out, i, command = [], 0, ""
    while i < len(d):
        ch = d[i]
        if ch in _SEPARATORS:
            i += 1
        elif ch.isalpha():
            command = ch
            out.append(ch)
            i += 1
        elif command.lower() in ("", "z"):
            raise ValueError(f"Number without a command near: {d[i:i + 20]!r}")
        else:
            for n in range(_ARG_COUNTS[command.lower()]):
                while i < len(d) and d[i] in _SEPARATORS:
                    i += 1
                if command.lower() == "a" and n in (3, 4):
                    out.append(d[i])  # arc flags are always a single 0 or 1
                    i += 1
                else:
                    match = _NUMBER.match(d, i)
                    if not match:
                        raise ValueError(f"Bad path data near: {d[i:i + 20]!r}")
                    out.append(match.group())
                    i = match.end()
    return " ".join(out)


def expand_svg(svg: str) -> str:
    return re.sub(r'\bd="([^"]*)"', lambda m: f'd="{expand_path(m.group(1))}"', svg)


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n")


def write_imageset(name: str, filename: str, svg: str, template: bool) -> None:
    imageset = ASSETS / "Services" / f"{name}.imageset"
    imageset.mkdir()
    (imageset / filename).write_text(svg)
    write_json(imageset / "Contents.json", {
        "images": [{"filename": filename, "idiom": "universal"}],
        "info": {"author": "xcode", "version": 1},
        "properties": {
            "preserves-vector-representation": True,
            "template-rendering-intent": "template" if template else "original",
        },
    })


def main(package: Path, logos_package: Path) -> None:
    color_logos = json.loads((logos_package / "icons.json").read_text())
    data = json.loads((package / "data" / "simple-icons.json").read_text())
    icons = data if isinstance(data, list) else data["icons"]
    hex_by_slug = {icon.get("slug"): icon["hex"] for icon in icons if icon.get("slug")}
    hex_by_title = {icon["title"]: icon["hex"] for icon in icons}

    ASSETS.mkdir(parents=True, exist_ok=True)
    write_json(ASSETS / "Contents.json", {"info": {"author": "xcode", "version": 1}})
    services_dir = ASSETS / "Services"
    shutil.rmtree(services_dir, ignore_errors=True)
    services_dir.mkdir(parents=True)
    write_json(services_dir / "Contents.json", {"info": {"author": "xcode", "version": 1}})

    entries = []
    for slug, name, domains, fallback_hex in SERVICES:
        mono_svg = package / "icons" / f"{slug}.svg"
        color_name = PREFER_COLOR.get(slug) or (None if mono_svg.exists() else COLOR_LOGOS.get(slug))
        if slug in OFFICIAL_LOGOS:
            filename, logo = OFFICIAL_LOGOS[slug]
            imageset = ASSETS / "Services" / f"service-{slug}.imageset"
            imageset.mkdir()
            shutil.copy(ROOT / "Scripts" / "logos" / filename, imageset / filename)
            write_json(imageset / "Contents.json", {
                "images": [{"filename": filename, "idiom": "universal"}],
                "info": {"author": "xcode", "version": 1},
                "properties": {"template-rendering-intent": "original"},
            })
            color = fallback_hex
        elif slug in LETTER_ONLY:
            logo = ".none"
            color = hex_by_slug.get(slug) or hex_by_title.get(name) or fallback_hex
        elif color_name:
            icon = color_logos["icons"][color_name]
            width = icon.get("width", color_logos.get("width", 256))
            height = icon.get("height", color_logos.get("height", 256))
            svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}">'
                   f'{expand_svg(icon["body"])}</svg>\n')
            write_imageset(f"service-{slug}", f"{slug}.svg", svg, template=False)
            logo = ".color"
            color = hex_by_slug.get(slug) or hex_by_title.get(name) or fallback_hex
        elif mono_svg.exists():
            write_imageset(f"service-{slug}", f"{slug}.svg", expand_svg(mono_svg.read_text()), template=True)
            logo = ".monochrome"
            color = hex_by_slug.get(slug) or hex_by_title.get(name)
        else:
            logo = ".none"
            color = fallback_hex
        if color is None:
            sys.exit(f"No color for {slug}: add a fallback hex")
        domain_list = ", ".join(f'"{d}"' for d in domains)
        entries.append(
            f'        KnownService(id: "{slug}", name: "{name}", domains: [{domain_list}], '
            f"color: 0x{color.upper()}, logo: {logo}),"
        )

    SWIFT_OUT.write_text(
        "// Generated by Scripts/generate_services.py — do not edit by hand.\n\n"
        "extension KnownService {\n"
        "    static let all: [KnownService] = [\n"
        + "\n".join(entries)
        + "\n    ]\n}\n"
    )
    count = {kind: sum(f"logo: {kind})" in e for e in entries)
             for kind in (".monochrome", ".color", ".appIcon", ".none")}
    print(f"{len(entries)} services: {count['.monochrome']} single-color logos, "
          f"{count['.color']} full-color logos, {count['.appIcon']} app icons, {count['.none']} letter only")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))
