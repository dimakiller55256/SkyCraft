# Third-party notices

SkyCraft is MIT-licensed (see `LICENSE`). A release also contains, or is built from, the following.

## In the Skyrim plugin (`SkyCraft.dll`)

| Component | License | Source |
|---|---|---|
| CommonLibSSE-NG | MIT | https://github.com/alandtse/CommonLibVR/tree/ng |
| spdlog | MIT | https://github.com/gabime/spdlog |
| {fmt} | MIT | https://github.com/fmtlib/fmt |
| xbyak | BSD-3-Clause | https://github.com/herumi/xbyak |
| SimpleIni | MIT | https://github.com/brofield/simpleini |

## In the bundled Minecraft (`SkyCraft-Minecraft.zip`)

| Component | License | Source |
|---|---|---|
| Prism Launcher (unmodified portable Windows build) | GPL-3.0 | https://github.com/PrismLauncher/PrismLauncher (the version is in the bundle's `THIRD-PARTY.txt`) |
| Fabric API | Apache-2.0 | https://github.com/FabricMC/fabric |

The bundle carries Prism Launcher's full license text as `Prism/LICENSE-PrismLauncher.txt`.

## Not included

Minecraft, Java and Fabric Loader aren't included. Prism Launcher downloads them from Mojang,
the Java vendor and FabricMC after the player signs in with a Microsoft account that owns
Minecraft: Java Edition. Skyrim, SKSE and the Address Library aren't included either.

SkyCraft isn't affiliated with or endorsed by Mojang, Microsoft, Bethesda or ZeniMax.

## Separate EasyLAN adaptation

The `easylan/` subproject is an adaptation of EasyLAN 1.6a by XiaoXianHW / Darf,
licensed GPL-3.0-only. Its license, upstream provenance, build and component
notices are in that directory. The repository's root license does not replace
the EasyLAN subproject's license. Its standalone package includes sources and
the unchanged compatible Fabric API.
