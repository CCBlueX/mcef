<p align="center">
  <img src="https://github.com/CinemaMod/mcef/assets/30220598/938896d7-2589-49df-8f82-29266c64dfb7" alt="MCEF Logo" style="width:66px;height:66px;">
</p>

# MCEF (Minecraft Chromium Embedded Framework)
A fork of the MCEF mod and library for adding the Chromium web browser into Minecraft.

MCEF is based on java-cef (Java Chromium Embedded Framework), which is based on CEF (Chromium Embedded Framework), which is based on Chromium. It was originally created by montoyo. It was rewritten by the CinemaMod Group.

MCEF contains a downloader system for downloading the java-cef & CEF binaries required by the Chromium browser. This requires a connection to https://api.liquidbounce.net.

Current Chromium version: `CEF 122.1.10+gc902316+chromium-122.0.6261.112`

## Supported Platforms
- Windows 10/11 (x86_64, arm64)*
- macOS 11 or greater (Intel, Apple Silicon)
- GNU Linux glibc 2.31 or greater (x86_64, arm64)**

*Some antivirus software may prevent MCEF from initializing. You may have to disable your antivirus or whitelist the mod files for MCEF to work properly.

**This mod will not work on Android.

## For Modders
MCEF is LGPL, as long as your project doesn't modify or include MCEF source code, you can choose a different license. Read the full license in the LICENSE file in this directory.

### Using MCEF in Your Project
```
repositories {
    maven { url 'https://jitpack.io' }
}
```
```
dependencies {
    modImplementation 'com.github.CCBlueX:mcef:1.3.0-1.21.1'
}
```

### Building & Modifying MCEF
After cloning this repo, you will need to clone the java-cef git submodule. There is a gradle task for this: `./gradlew cloneJcef`.

## Fork Hirarchy
- [CCBlueX/mcef](https://github.com/CCBlueX/mcef)
- [CinemaMod/mcef](https://github.com/CinemaMod/mcef)
- [montoyo/mcef](https://github.com/montoyo/mcef)
