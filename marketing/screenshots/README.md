# Onym Android marketing screenshots

Five English and five Russian Google Play compositions, each 1244×2424.
Every phone screen is a genuine localized capture from the app; the original
Fastlane screenshots are not modified.

The visual system mirrors onym.app: `#08080a` background, `#f5f5f7` display
typography, muted gray supporting copy, SF Mono labels, and thin hairlines.
Each capture is placed inside an Android-specific phone shell with rounded
screen clipping, centered punch-hole camera, edge highlight, and side controls.

The five-slide story follows the website's actual app narrative: it starts as
a familiar messenger, opens into an encrypted conversation, reveals the
device-owned identity, then shows the separate Nostr courier and Stellar
notary. Every infrastructure claim is paired with its real Android settings
screen.

Rebuild with:

```sh
node marketing/screenshots/build-marketing.mjs
```

The script requires macOS Quick Look and `ffmpeg` on `PATH`. Set `FFMPEG` to
an explicit binary path when needed.

The committed outputs are the source of truth for the Play listing. The
`Play Store Metadata` GitHub workflow copies them into Fastlane's metadata tree
and uploads the localized listing text, icon, feature graphic, and screenshots.
