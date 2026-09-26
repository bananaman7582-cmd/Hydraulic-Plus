# Hydraulic

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)

Hydraulic is a companion to Geyser which allows for Bedrock players to join modded Minecraft: Java Edition servers. 

Hydraulic is an open collaboration project by [CubeCraft Games](https://cubecraft.net).

## About this fork

This is [Hydraulic-Plus](https://github.com/bananaman7582-cmd/Hydraulic-Plus), an unofficial fork
maintained by me, **Bananaman**, built on top of the original work by
[GeyserMC](https://github.com/GeyserMC/Hydraulic) and [CubeCraft Games](https://cubecraft.net). All
credit for Hydraulic itself belongs to them; this fork only adds to it.

It is **not** affiliated with or endorsed by GeyserMC, and issues found here should not be reported to
the upstream tracker. What this fork adds over upstream:

- A port to Minecraft 26.2
- Entity model and animation support for mods that define their models in client code, including
  GeckoLib and Citadel mods
- Coexistence with [Polymer](https://github.com/Patbox/polymer): Java players keep Polymer's
  vanilla-facing disguises while Bedrock players are sent the real modded blocks and items
- Modded fluids drawn as stand-in blocks, block light emission, and a number of block and item
  conversion fixes

Built against Minecraft 26.2 and requires JDK 25.

## What is Hydraulic?
Hydraulic is a server-side mod, which allows for Bedrock players to join modded Minecraft: Java Edition servers. This project works alongside [Geyser](https://github.com/GeyserMC/Geyser) to make this possible.

### This project is still in very early development and should not be used on production setups! You can get [Hydraulic](https://geysermc.org/download?project=other-projects&hydraulic=expanded) from the GeyserMC website.

## Contributing
Any contributions are appreciated. Please feel free to reach out to us on [Discord](https://discord.gg/geysermc) if
you're interested in helping out with Hydraulic.

### Project Setup
1. Clone the repo to your computer.
2. Navigate to the Hydraulic root directory and run `git submodule update --init --recursive`. This command downloads all the needed submodules for Hydraulic and is a crucial step in this process.
3. If your default JVM/JDK is not Java 25, please set your IDE to use a valid Java 25 JVM. Otherwise, you will run into an error while building Hydraulic. 
4. The project should import into your IDE after the loom setup is complete. For more detailed information, see the [Fabric setup](https://docs.fabricmc.net/develop/getting-started/setting-up).
5. Use `./gradlew build` to compile a jar file, or use `./gradlew :fabric:runServer` to run a server with Hydraulic installed. Make sure you have Geyser in your `mods` folder along with Hydraulic!

## Links:
- Website: https://geysermc.org
- Docs: https://geysermc.org/wiki/other/hydraulic
- Download: https://geysermc.org/download?project=other-projects&hydraulic=expanded
- Discord: https://discord.gg/geysermc
- Donate: https://opencollective.com/geysermc
