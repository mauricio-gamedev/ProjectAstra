# Project Astra

Experimental Minecraft: Java Edition launcher foundation for Android.

> **Status:** `0.1.0-alpha01` foundation only. This repository does not yet launch Minecraft.

## First milestone

The first scaffold establishes:

- Kotlin + Jetpack Compose Android application
- Android API 26 minimum
- compileSdk 36 / targetSdk 36
- AGP 9.2 built-in Kotlin
- persistent offline account profiles
- stable Minecraft-style offline UUID generation
- default-account selection
- device profiling foundation
- models for instances, renderers, and performance modes
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

## Build on GitHub

Push the project to GitHub and run **Actions → Android Debug APK → Run workflow**. The workflow uploads `app-debug.apk` as an artifact.

## Next milestone

1. Minecraft version manifest and downloader
2. Runtime selection (Java 8 / 17 / 21)
3. Microsoft OAuth provider
4. Launch-plan builder (classpath, assets, natives, JVM/game args)
5. Renderer abstraction
6. First real Minecraft launch

## Licensing

The code in this initial scaffold is original project code. No Zalith, Pojav, Amethyst, renderer, or Minecraft assets/code are vendored in this milestone. Any third-party component added later must retain and comply with its own license and notices.
