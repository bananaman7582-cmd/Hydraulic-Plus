# Hydraulic

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)

Hydraulic is a companion to Geyser which allows for Bedrock players to join modded Minecraft: Java Edition servers. 

Hydraulic is an open collaboration project by [CubeCraft Games](https://cubecraft.net).

## About this fork

This is [Hydraulic-Plus](https://github.com/bananaman7582-cmd/Hydraulic-Plus), an unofficial fork
maintained by **Bananaman**, built on top of the original work by
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

## How to use

### What you need

A **Fabric server on Minecraft 26.2** with these mods installed alongside Hydraulic-Plus:

| Mod | Why |
|---|---|
| [Fabric API](https://modrinth.com/mod/fabric-api) | required by Hydraulic |
| [Geyser](https://geysermc.org/download) (Fabric build) | lets Bedrock clients connect at all |
| [Floodgate](https://geysermc.org/download) (Fabric build) | lets them connect without a Java account |

Hydraulic is **server-side**. Bedrock players install nothing; they join through Geyser as usual.

### Installing

1. Drop the jar into your server's `mods` folder, next to Geyser and Floodgate.
2. Start the server. On the first start Hydraulic converts each mod's assets into Bedrock resource
   packs, which takes **tens of seconds to a few minutes** depending on how many mods you have.
3. Join from Bedrock. Accept the resource pack when prompted - the modded blocks, items and mobs live
   in it, so declining leaves you seeing nothing.

Converted packs are cached under `config/hydraulic/storage`, so later starts are fast. They are
rebuilt whenever Hydraulic's own jar changes, so expect one slow start after every update.

### Raise the watchdog timeout before your first start

Conversion runs while the server is starting, and Minecraft's watchdog kills a server whose tick
takes longer than 60 seconds - which a large modpack's first conversion genuinely can. In
`server.properties`:

```properties
max-tick-time=180000
```

Without this, a big pack can force-shut the server down mid-conversion. Restarting simply repeats it,
so this is worth setting before the first start rather than after.

### Mobs from mods that are not GeckoLib

Some mods define their entity models in client-only code rather than shipping model files, so a
dedicated server has no shape to send to Bedrock. Those mobs arrive as a stand-in (a pig, a bat)
until their real models have been read once:

1. Launch the **Java client** once with exactly the same mods installed.
2. Join any world - singleplayer is fine - and let the mobs in question appear on screen.
3. Copy `config/hydraulic/entity-geometry` from that client into the server's `config/hydraulic`,
   then restart the server.

GeckoLib mods need none of this; their models are already in a format Bedrock reads. The server log
names any entity still waiting on a model, and says which of these two cases it is.

### Running alongside Polymer

If your server runs [Polymer](https://github.com/Patbox/polymer), both mods are solving the same
problem for different audiences: Polymer disguises modded content as vanilla so unmodified **Java**
clients see something sensible, while Hydraulic converts it properly for **Bedrock**. Hydraulic keeps
the two apart automatically - Java players keep their disguises, Bedrock players are sent the real
blocks and items. To turn that off, in `config/hydraulic/config.json`:

```json
{ "hidePolymerFromBedrock": false }
```

### Building it yourself

Requires **JDK 25**:

```bash
./gradlew build
```

The jar is written to `fabric/build/libs/hydraulic-fabric.jar`.

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
