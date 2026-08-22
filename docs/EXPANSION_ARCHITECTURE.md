# Project Astra Next — expansion architecture

This branch intentionally does **not** replace the proven alpha38 rendering/input core.
The current public repository did not contain the launcher source tree, so the expansion
is being built as an Android-safe Kotlin module that can be connected to the reconstructed
launcher core without rewriting MobileGlues/EGL/Surface/input.

## Modules

1. **Loader framework** — Vanilla, Fabric, Quilt, Forge and NeoForge discovery/planning.
2. **Mod manager** — Modrinth search/version resolution, dependency-aware install plans,
   integrity verification and per-instance mod directories.
3. **Compatibility engine** — detects known incompatible combinations and suppresses
   risky optimizer actions when performance/render mods already own a subsystem.
4. **Smart optimization engine** — Compatibility/Balanced/Performance/Experimental
   policies that emit explicit launch and options changes instead of opaque "boost" flags.
5. **Launch extension pipeline** — merges loader/mod/optimization decisions into an
   existing launch plan without mutating the protected graphics bridge.

## Safety rules

- Never patch Minecraft classes or arbitrary memory as a generic optimization.
- Never add a JVM flag twice.
- Every optimization declares scope, risk and conflicts.
- Mod-aware rules win over generic renderer tuning.
- Instance isolation prevents loaders/mods from leaking between installations.
- Downloads must have an expected hash when the remote provider exposes one.
- The launcher core remains responsible for Microsoft authentication and token secrecy.

## Integration target

The expansion module produces `LaunchExtensionResult` and `LoaderInstallPlan` objects.
The reconstructed Astra launcher consumes them immediately before its existing Java/JLI
launch path. This keeps the working alpha38 native bridge as the compatibility baseline.
