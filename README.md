# Project Astra

Experimental Minecraft: Java Edition launcher foundation for Android.

> **Status:** `0.1.0-alpha02`. The app builds, manages offline accounts and instances, indexes official Minecraft versions, and can install the shared client/libraries/assets needed for a selected version. It does not launch Minecraft yet because the Android Java runtime/native launch layer is the next milestone.

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
- verified version metadata download
- verified Minecraft client JAR download
- shared library download cache
- shared asset index/object download cache
- SHA-1 validation and `.part` atomic downloads
- Java major-version discovery from Mojang metadata
- optional stable development signing through GitHub Actions secrets
- models for renderers and performance modes

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
├── install
│   ├── InstallModels
│   └── VersionInstaller
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

## Version and file installation

Project Astra reads Mojang's official Java Edition version manifest from:

```text
https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
```

For an installation, the launcher downloads the selected version metadata, `client.jar`, library artifacts, asset index, and content-addressed asset objects. Files with published SHA-1 values are verified before they are accepted. Downloads first go to `.part` files so an interrupted transfer is not mistaken for a valid game file.

Shared data is stored under the app's Minecraft root so multiple instances using the same version/assets do not duplicate those files.

## APK signing

The debug CI can use one stable development certificate when these GitHub Actions secrets are configured:

- `ASTRA_DEV_KEYSTORE_BASE64`
- `ASTRA_DEV_KEYSTORE_PASSWORD`
- `ASTRA_DEV_KEY_ALIAS`
- `ASTRA_DEV_KEY_PASSWORD`

See `docs/SIGNING.md`. The future Play Store/release key must be separate and private.

## Build on GitHub

Run **Actions → Android Debug APK → Run workflow**. The workflow uploads `app-debug.apk` as an artifact.

## Next milestone

1. Android Java Runtime Manager driven by each Minecraft version's metadata
2. Runtime install/selection for Java 8 / 17 / 21 and newer required majors
3. Launch-plan builder (classpath, assets, JVM/game args)
4. Android-native LWJGL/renderer integration
5. Microsoft OAuth provider
6. Renderer abstraction
7. First real Minecraft launch

## Licensing

The launcher code in these milestones is original project code. No Zalith, Pojav, Amethyst, renderer, or Minecraft assets/code are vendored in the repository. Runtime/rendering components added later must retain and comply with their own licenses and notices.
