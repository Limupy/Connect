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
