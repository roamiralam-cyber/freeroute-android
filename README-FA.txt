# FreeRoute patch — 8 Nov 2026

This patch integrates the FreeRoute panel into the existing sing-box Android app and adds a local-config import path for supported share links.

## Included files
- `app/src/main/java/io/nekohasekai/sfa/freeroute/FreeRouteCatalog.kt`
  - Fetches from three public community feeds.
  - Parses VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and TUIC links.
  - Checks TCP connection latency concurrently and sorts the list.
  - Converts supported links into a local sing-box JSON configuration.
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/FreeRoutePanel.kt`
  - Scan public server lists.
  - Add a V2Ray subscription URL (one-time import to a local profile).
  - Import a single share link manually.
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt`
  - Adds the panel to the existing Dashboard without removing existing cards.
- `app/src/main/java/io/nekohasekai/sfa/compose/navigation/ProfileRoutes.kt`
- `app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt`
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/configuration/NewProfileScreen.kt`
- `app/src/main/java/io/nekohasekai/sfa/compose/screen/configuration/NewProfileViewModel.kt`
  - Carries generated local JSON through the existing new-profile flow and validates it using Libbox.

## Apply the patch
In the `dev` branch on GitHub, replace each file at the exact same path with the corresponding file in this archive. Commit all changes, then run GitHub Actions.

## Known limitations
- The scanner checks three fixed public feeds; it does not crawl the entire internet.
- The displayed `ms` value is TCP connection latency, not actual download throughput.
- Real download speed must be measured through a running sing-box tunnel. That is not implemented in this patch.
- A subscription is downloaded once and converted into a local profile snapshot. Automatic subscription refresh is not implemented yet.
- URI-to-sing-box conversion covers common VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and TUIC variants. Vendor-specific URI extensions and uncommon transports may still fail Libbox validation.
- The changes have not been built against Android SDK/NDK or tested on a phone in this environment. This is source code, not a verified APK.
- Public free proxies are untrusted. Do not send sensitive credentials or private data through them.

## Security warning
The uploaded source archive contained `app/release.keystore`. It is intentionally not included in this patch. If that is your private signing key and the GitHub repository is public, treat it as exposed, rotate the release key, and keep keystores/passwords out of public source control.
