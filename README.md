# Project Astra

Experimental Minecraft: Java Edition launcher for Android.

> **Status:** `0.1.0-alpha04`. The app builds, manages offline accounts and instances, installs official Minecraft files, installs Android Java runtimes, and can assemble a validated Minecraft launch plan. Actual game execution remains blocked until the Android LWJGL/renderer/native layer is integrated.

## Current milestone

The current alpha establishes:

- Kotlin + Jetpack Compose Android application
- Android API 26 minimum
- compileSdk 36 / targetSdk 36
- AGP 9.2 built-in Kotlin
- adaptive universe-style launcher icon
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
- Android Java Runtime Manager for supported Java 8 / 17 / 21 / 25 packages
- runtime architecture detection for arm, arm64, x86 and x86_64
- runtime archive download, SHA-256 validation and `.tar.xz` extraction
- launch-plan builder for classpath, JVM args, game args, placeholders and offline accounts
- Mojang metadata rule evaluation for OS/features
- per-instance game/native directories and environment plan
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
├── launch
│   ├── LaunchModels
│   ├── LaunchPlanBuilder
│   └── MinecraftRuleEvaluator
├── runtime
│   ├── RuntimeCatalog
│   ├── RuntimeInstaller
│   ├── RuntimeModels
│   └── RuntimeViewModel
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

## Android Java runtimes

The runtime manager detects the Android ABI, selects the Java major requested by Minecraft metadata, downloads an Android-compatible OpenJDK runtime, validates available SHA-256 digests, safely extracts the archive, and records the installed runtime for reuse.

Runtime packages are external third-party components and are not committed to this repository. Their upstream licensing and notices apply independently.

## Launch-plan builder

Once a Minecraft version and its required Java runtime are installed, Astra can prepare a launch plan containing:

- Java executable and `JAVA_HOME`
- instance working directory
- resolved library classpath + client JAR
- Mojang JVM arguments
- modern or legacy game arguments
- account/version/assets placeholders
- memory allocation from the instance profile
- renderer/performance-mode environment hints

The plan is intentionally not executed yet. Desktop Mojang native classifiers are not treated as Android natives. The next runtime milestone will inject the Android LWJGL and renderer/native compatibility layer before JVM execution is enabled.

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

1. Android LWJGL native layer
2. renderer abstraction and first MobileGlues integration
3. native/library environment injection into the launch plan
4. JVM process bootstrap and log capture
5. first real Minecraft window/boot
6. Microsoft OAuth provider
7. performance/renderer auto-selection

## Licensing

The launcher-owned code in these milestones is original project code. Minecraft files and Java runtimes are downloaded at runtime and are not vendored into the repository. Any renderer, LWJGL compatibility layer, runtime package, or other third-party component must keep its applicable license and notices.
