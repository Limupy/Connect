# Limu Connect

Repository for the Limu Minecraft client mod, its release artifacts, icon and the public e4steam address/status document.

## Project layout

- `mod/` — NeoForge 1.21.1 client mod source and Gradle build.
- `limu/connect.json` — public directory document used by the mod to refresh the LimuServer address and heartbeat.
- `favicon.svg` — Limu icon shared by the project.
- `.github/workflows/build.yml` — automated mod build.

## Build

Open `mod/` with Java 21 and Gradle 9.2.1, or run:

```sh
cd mod
gradle --no-daemon clean build
```

The workflow uploads a `LimuConnect-NeoForge-1.21.1` JAR artifact for each successful build.


## Mod synchronization beta

The `mod-sync` branch is an experimental client build line. It is intentionally separate from stable `main`.

- Mod version: `1.3.0-sync.1`
- Display name: `Limu Connect Sync Beta`
- Manifest: `limu/mods.json`
- Only mods explicitly marked as client-required in the manifest may be downloaded.
- Downloads must use HTTPS and pass a SHA-256 check before being placed in the active NeoForge `mods` directory.
- Downloaded mods require a full Minecraft restart before NeoForge can load them.
- Existing mods and unrelated client files are never deleted as part of a missing-mod install.
- Server-only mods are not distributed to clients.
