# Project Astra

Project Astra is an experimental, performance-focused Minecraft Java launcher for Android.

## Status

`0.1.0-alpha01` — foundation phase.

Current foundation includes:

- Kotlin + Jetpack Compose Android app shell
- Offline account profiles with stable UUIDs
- Multiple local accounts and default account selection
- Device profiling foundation
- Renderer abstraction models (Auto, MobileGlues, GL4ES, ANGLE, Zink)
- Performance profiles (Performance, Balanced, Quality, Adaptive)
- Instance model foundation
- GitHub Actions debug APK build workflow

## Planned next milestones

1. Version Manager
2. Java Runtime Manager (Java 8 / 17 / 21)
3. Minecraft asset/library downloader
4. Launch argument builder
5. Renderer integration
6. Fabric/Forge/NeoForge support
7. Microsoft authentication
8. Adaptive optimization engine

## Offline accounts

Offline accounts are a first-class supported option. They are intended for single-player, LAN, and servers configured to permit offline players. Project Astra does not attempt to bypass authentication on servers that require authenticated Minecraft accounts.

## Project direction

The launcher is being designed as a modular Android-native frontend around isolated runtime, account, renderer, instance, and optimization subsystems so each can evolve independently.

## Copyright and license

Copyright © 2026 **mauricio-gamedev**. All rights reserved.

Project Astra launcher-owned source code, UI, documentation, branding, build configuration, and other original project material are proprietary unless explicitly stated otherwise. Public repository visibility does not grant a general license to copy, modify, redistribute, commercialize, rehost, or create derivative works.

See [`LICENSE`](LICENSE) and [`COPYRIGHT.md`](COPYRIGHT.md) for the Project Astra terms. Third-party components remain subject to their own copyright and license requirements and are not claimed as Project Astra property.
