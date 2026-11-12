# Limu Connect

Client-side NeoForge mod for Minecraft 1.21.1. Current release: **1.1.9**.

## What it does

- Keeps one **LimuServer** entry in the vanilla Multiplayer server list.
- Uses the normal server-list row, icon, selection and Join action.
- Fetches the current e4steam address from the Connect repository whenever the Multiplayer screen opens or the list refresh button is pressed.
- Applies an address from a valid directory response even if the heartbeat is stale; stale heartbeat data is shown as unconfirmed, not as proof that e4steam is offline.
- Saves the current address in Minecraft's ordinary `servers.dat` file.
- Does not change e4steam authentication, Steam credentials, or the user's other server entries.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1+
- e4steam 0.3.2+
- Steam running and signed in

## Build

Requires Java 21 and Gradle 9.2.1:

```sh
gradle --no-daemon clean build
```

The client JAR is written to `build/libs/`.

## Status document

The client reads the raw JSON file maintained in this repository:

`https://raw.githubusercontent.com/Limupy/Connect/main/limu/connect.json`
