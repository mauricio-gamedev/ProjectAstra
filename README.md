# Project Astra

Experimental Minecraft: Java Edition launcher foundation for Android.

> **Status:** `0.1.0-alpha01` foundation. The app builds and now includes the first real Version Manager + persistent instance profiles, but it does not launch Minecraft yet.

## Current milestone

The current alpha establishes:

- Kotlin + Jetpack Compose Android application
- Android API 26 minimum
- compileSdk 36 / targetSdk 36
- AGP 9.2 built-in Kotlin
- persistent offline account profiles
- stable Minecraft-style offline UUID generation
- default-account selection
- device profiling foundation
- Mojang `version_manifest_v2.json` integration
- latest release/snapshot detection
- persistent Minecraft instance profiles
- validation of selected versions against the Mojang manifest
- models for renderers and performance modes
- GitHub Actions debug APK build
- placeholders for Microsoft authentication and Minecraft runtime integration

## Architecture direction

```text
app
├── account
│   ├── AccountProfile
│   ├── AccountStore
│   ├── AccountViewModel
│   └── OfflineAccountService
├── core
│   ├── DeviceProfiler
│   └── LauncherModels
├── instance
│   ├── InstanceStore
│   └── InstanceViewModel
├── version
│   ├── MinecraftVersion
│   └── VersionManifestService
└── ui
    ├── AstraApp
    └── AstraTheme
```

Planned modules:

```text
launcher-core
runtime-manager
version-manager
renderer-api
renderer-mobileglues
renderer-gl4es
renderer-zink
performance-engine
control-engine
mod-manager
```

## Offline accounts

Offline mode creates a deterministic UUID using the conventional input:

```text
OfflinePlayer:<username>
```

Offline profiles are intended for local/single-player use and servers configured to accept offline identities. They are not intended to bypass authentication on servers that require authenticated Microsoft/Minecraft accounts.

## Version Manager

Project Astra reads Mojang's official version manifest from:

```text
https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
```

The alpha indexes available Java Edition versions, detects the latest release and snapshot, and validates instance version IDs before saving them locally.

## Build on GitHub

Push the project to GitHub and run **Actions → Android Debug APK → Run workflow**. The workflow uploads `app-debug.apk` as an artifact.

## Next milestone

1. Version metadata resolver and Minecraft client downloader
2. Library + asset index downloader with SHA-1 validation
3. Runtime selection (Java 8 / 17 / 21)
4. Microsoft OAuth provider
5. Launch-plan builder (classpath, assets, natives, JVM/game args)
6. Renderer abstraction
7. First real Minecraft launch

## Licensing

The code in this initial scaffold is original project code. No Zalith, Pojav, Amethyst, renderer, or Minecraft assets/code are vendored in this milestone. Any third-party component added later must retain and comply with its own license and notices.
