# Third-party notice policy

Project Astra keeps launcher-owned code separate from third-party runtime, renderer, game, and compatibility components.

The repository does not vendor source code or binary assets from Zalith Launcher, PojavLauncher, Amethyst, MobileGlues, GL4ES, Mesa/Zink, Minecraft, Mojang, or Microsoft unless an explicit future change records the relevant license and redistribution obligations.

## Android OpenJDK runtimes

The Runtime Manager can download Android OpenJDK runtime archives from the external `AngelAuraMC/angelauramc-openjdk-build` project when the user chooses to install a Java runtime. Those archives are downloaded at runtime rather than committed to this repository, and their upstream license/notices remain applicable to the downloaded component.

## MobileGlues

Starting with Project Astra `0.1.0-alpha06`, the launcher can download the official MobileGlues 2.0.0 APK from `MobileGL-Dev/MobileGlues-release` at runtime, verify the published SHA-256, and extract only the native libraries matching the current Android ABI into the launcher's private component directory.

MobileGlues remains a separate third-party component. Its upstream project is licensed under LGPL-2.1 and its upstream copyright/license terms continue to apply. Project Astra does not claim ownership of MobileGlues and does not commit the MobileGlues APK or its native binaries into this repository.

## Android LWJGL compatibility components

Starting with Project Astra `0.1.0-alpha07`, the launcher can download Android-compatible LWJGL component JARs at runtime from the public `AngelAuraMC/Amethyst-Android` project. The component catalog is pinned to upstream commit `360d708262ff703d9b52782d20cd348410a33df5`; each downloaded file is checked against its expected Git blob SHA-1 and exact size before use.

The Astra repository and APK do not embed those third-party JARs. The Amethyst repository at the pinned revision carries the GNU Lesser General Public License version 3, and the downloaded compatibility components can also contain upstream LWJGL material with its own applicable notices. Those upstream terms remain applicable to the downloaded files.

## Minecraft files

Minecraft version metadata, client files, libraries, and assets are fetched from Mojang-provided endpoints at runtime and are not redistributed inside the Project Astra repository or APK.

## Interoperability research

Public PojavLauncher/Amethyst sources are used as interoperability references for Android JVM/LWJGL behavior. The Astra native bridge introduced in `0.1.0-alpha06` is launcher-owned code using standard Android dynamic loading APIs and the public OpenJDK `libjli`/`JLI_Launch` interface; Pojav/Amethyst source is not copied into that bridge.

The `0.1.0-alpha07` ANativeWindow probe is also Astra-owned code built directly on Android NDK Surface/ANativeWindow APIs. It exists to validate the graphics-surface handoff before actual Minecraft JVM execution is enabled.

Before any renderer, LWJGL compatibility layer, runtime package, library, or other third-party component is vendored, linked, redistributed, or modified, its applicable license and notice obligations must be reviewed and recorded here.
