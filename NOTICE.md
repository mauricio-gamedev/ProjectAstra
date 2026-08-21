# Third-party notice policy

Project Astra keeps launcher-owned code separate from third-party runtime, renderer, game, and compatibility components.

The repository does not vendor source code or binary assets from Zalith Launcher, PojavLauncher, Amethyst, MobileGlues, GL4ES, Mesa/Zink, Minecraft, Mojang, or Microsoft.

The Runtime Manager can download Android OpenJDK runtime archives from the external `AngelAuraMC/angelauramc-openjdk-build` project when the user chooses to install a Java runtime. Those archives are downloaded at runtime rather than committed to this repository, and their upstream license/notices remain applicable to the downloaded component.

Minecraft version metadata, client files, libraries, and assets are fetched from Mojang-provided endpoints at runtime and are not redistributed inside the Project Astra repository or APK.

Before any renderer, LWJGL compatibility layer, runtime package, library, or other third-party component is vendored, linked, redistributed, or modified, its applicable license and notice obligations must be reviewed and recorded here.
