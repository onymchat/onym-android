# Play store graphics (source)

HTML sources for the Google Play listing images. Rendered PNGs live in
the supply metadata tree and are uploaded by `fastlane upload_all`:

| Source | Output | Play requirement |
|---|---|---|
| `icon.html` | `metadata/android/{en-US,ru-RU}/images/icon.png` | 512×512 app icon |
| `feature-en.html` | `metadata/android/en-US/images/featureGraphic.png` | 1024×500 feature graphic |
| `feature-ru.html` | `metadata/android/ru-RU/images/featureGraphic.png` | 1024×500 feature graphic |

Built from the app's brand: the launcher mark
(`app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`, embedded as
a data URI), brand ink `#0A0A0C`, and accent purple `#8B4DEB`.

## Regenerate

Edit the `.html`, then render with headless Chrome (writes the PNG, then
hangs on exit — that's fine, just kill it):

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 --user-data-dir="$(mktemp -d)" \
  --window-size=512,512 --screenshot=icon.png icon.html
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 --user-data-dir="$(mktemp -d)" \
  --window-size=1024,500 --screenshot=feature-en.png feature-en.html
```
