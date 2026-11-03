# Scratch Android store assets

Upload these files in Google Play Console.

## Required graphics

| Asset | Path | Size |
| --- | --- | --- |
| App icon | `graphics/icon-512.png` | 512 × 512 px |
| Feature graphic | `graphics/feature-graphic-1024x500.png` | 1024 × 500 px |
| Phone screenshots | `screenshots/phone/01-main.png` … `04-settings.png` | 1920 × 1080 px |
| 7-inch tablet screenshots | `screenshots/tablet-7/01-main.png` … `04-settings.png` | 1920 × 1080 px (16:9) |
| 10-inch tablet screenshots | `screenshots/tablet-10/01-main.png` … `04-settings.png` | 2560 × 1440 px (16:9) |

Phone and tablet screenshots are 16:9 landscape and use the same app UI; replace the tablet set with fresh captures if you want device-specific chrome.

## Copy

- `listing.md` — title, short description, full description, release notes.
- `data-safety.md` — Play Console Data safety answers.
- Privacy policy: https://tkleisas.github.io/scratch/privacy/

## Build the signed artifacts

The release signing key is read from `keystore.properties` in the project root or
`~/.volna/keystore.properties`. Build both Play artifacts with:

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Verify 16 KB page-size support:

```bash
./scripts/check_16kb_page_size.sh \
  app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/bundle/release/app-release.aab
```

The AAB is the upload artifact for Google Play.
