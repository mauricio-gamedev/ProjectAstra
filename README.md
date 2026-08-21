# Project Astra

Experimental Minecraft: Java Edition launcher for Android.

> **Status:** `0.1.0-alpha10`. The app builds, manages offline accounts and instances, installs official Minecraft files and Android Java runtimes, assembles a validated Minecraft launch plan, and has passed on-device ANativeWindow + MobileGlues/EGL/OpenGL presentation. Alpha10 adds a persistent JVM flight recorder around the isolated OpenJDK `JLI_Launch` test.

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
- Android LWJGL compatibility component preparation
- MobileGlues 2.0.0 runtime component preparation
- Android Surface → `ANativeWindow` bridge
- MobileGlues EGL window/context bridge and real `eglSwapBuffers`
- isolated Android `:game` process for JVM/native execution
- OpenJDK `JLI_Launch` smoke-test bridge with stdout/stderr capture
- persistent cross-process JVM report/flight recorder when `:game` exits before UI reporting
- automatic report dialog in the launcher process after the game process returns
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
├── game
│   ├── GameSurfaceActivity
│   └── JvmSmokeReportStore
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
├── lwjgl
│   └── AndroidLwjglManager
├── nativebridge
│   ├── AstraNativeBridge
│   └── NativeRuntimeValidator
├── renderer
│   └── RendererComponentManager
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

Native bridge sources currently include the graphics bridge and a separate JLI launcher bridge so JVM work does not require rewriting the already validated graphics path.

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

## Launch pipeline

Once a Minecraft version and its required Java runtime are installed, Astra can prepare a launch plan containing:

- Java executable and `JAVA_HOME`
- instance working directory
- resolved library classpath + client JAR
- Mojang JVM arguments
- modern or legacy game arguments
- account/version/assets placeholders
- memory allocation from the instance profile
- renderer/performance-mode environment hints
- Android LWJGL compatibility classpath
- MobileGlues renderer environment

The alpha10 device test starts OpenJDK through the public `JLI_Launch` interface inside the isolated `:game` process. Before the call, Astra persists a `STARTED` marker and redirects stdout/stderr to app-private storage. If `JLI_Launch` returns, Astra records its result. If the game process exits first, the launcher process reads the marker and captured OpenJDK output when it resumes, so a clean launcher exit and a native crash are no longer visually indistinguishable.

The next launch milestone is the GLFW/LWJGL native-window callback layer followed by execution of the real Minecraft main class.

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

1. validate alpha10 persistent `JLI_Launch` report on a real Android device
2. implement Android GLFW/LWJGL native-window callbacks
3. hand the existing ANativeWindow/MobileGlues path to the LWJGL layer
4. execute the real Minecraft main class through the launch plan
5. add touch/input callbacks and lifecycle handling
6. Microsoft OAuth provider
7. performance/renderer auto-selection

## Copyright and license

Copyright © 2026 **mauricio-gamedev**. All rights reserved.

Project Astra launcher-owned source code, UI, documentation, branding, build configuration, and other original project material are proprietary unless explicitly stated otherwise. Public repository visibility does not grant a general license to copy, modify, redistribute, commercialize, rehost, or create derivative works.

See [`LICENSE`](LICENSE) and [`COPYRIGHT.md`](COPYRIGHT.md) for the Project Astra terms. Third-party components remain subject to their own copyright and license requirements and are not claimed as Project Astra property. See [`NOTICE.md`](NOTICE.md) for third-party/runtime notices.
