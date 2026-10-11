# Polymer Patcher++ compatibility gap

This inventory compares Hydraulic Plus with Polymer Patcher++ 0.14.337. The two projects serve
different clients: Polymer Patcher++ targets an unmodded Java client, while Hydraulic must produce
Bedrock assets and packets through Geyser. A Polymer carrier, display entity, text-glyph GUI, or
client registry-numbering patch is therefore not automatically portable to Hydraulic.

## Ranked implementation order

### 1. Low risk: generic resource and packet safety

| Polymer Patcher++ feature | Hydraulic status |
| --- | --- |
| Lenient model parsing | Implemented: old display transforms, code-rendered special models, code-backed tints, and invalid `cullface` values fall back without discarding the asset. |
| Missing translations | Implemented: mod language files are loaded into Geyser; missing English item/block names now receive readable generated fallbacks. |
| Safe recipe displays | Implemented: recipes with modded display types are removed from Bedrock recipe packets. |
| Safe statistics | Implemented: statistics using modded type/value registry ids are removed from Bedrock packets. |
| Inline modded sounds | Implemented. |
| Modded particle fallback | Implemented with name-based vanilla lookalikes. |
| Modded effect fallback | Implemented with conservative vanilla effect lookalikes plus real-name acquire/expire action-bar notices for the affected Bedrock player. |
| Modded attributes | Implemented by retaining only vanilla attributes. |
| Pack splitting | Not needed: Hydraulic already emits one independent pack per source mod. |
| Extra operator resource-pack overlays | Not copied: Hydraulic already accepts Geyser's normal Bedrock packs and emits a separate pack per mod; Polymer's Java-pack overlay host is a different delivery path. |
| Modded potion selector cleanup | Not needed in the Bedrock output: Hydraulic resolves the Java selector into a static Bedrock item definition. Code-backed selectors now take their explicit fallback before parsing. |
| Dynamic registry codec cleanup | Implemented for Bedrock configuration packets, including unknown biome choices, namespaced map keys, and modded enchantment conditions/effects. |
| Java mod custom payloads | Implemented: non-Minecraft/Geyser/Floodgate payload channels are withheld from Bedrock connections. |

### 2. Low-to-medium risk: generic blocks and items

| Polymer Patcher++ feature | Hydraulic status |
| --- | --- |
| Blocks and carrier allocation | Native Geyser custom blocks implemented; palette-safe empty geometry and over-sized/rotated geometry fallbacks implemented. |
| Block collision, sound, hardness, light and fluid stand-ins | Implemented in broad form; exact custom fluid physics and ambience remain open. |
| Item definitions and components | Implemented for stack size, durability, food, use animation/cooldown, tools, enchantability, equipment, repair, armor and attack damage. |
| Item model fallback | Implemented for references, special/base, condition, select, composite and range models, with a first-readable-model fallback. |
| Custom shields | Implemented generically from `BLOCKS_ATTACKS`, including Bedrock blocking behavior and generated/fallback held geometry. Dynamic per-stack shield variants remain open. |
| Bows and crossbows | Implemented in broad form. |
| Armor/equipment | Implemented for standard humanoid equipment, legacy `textures/armor` recovery, and glider/elytra attachables. Code-only player armor shapes still fall back to the standard wearable geometry. |
| Code-backed item tints | Untinted visibility fallback implemented; faithful per-stack tint state remains open. |
| Maps and custom map decorations | Implemented as packet-safe vanilla target markers while retaining position, rotation, label, and map pixels. Custom icon art is not expressible through the closed Bedrock marker set. |
| Held-item transforms | Partial through Java-model conversion; renderer-probed transforms remain open. |

### 3. Medium risk: menus and client presentation

| Polymer Patcher++ feature | Hydraulic status |
| --- | --- |
| Chest-grid custom menus | Implemented for provably compatible one-to-six-row layouts with real slot and click redirection. |
| Arbitrary inventory-based machine menus | Implemented where every control is an item slot that can be placed in a chest grid. Unsupported buttons are deliberately rejected. |
| Alex's Caves Nuclear Furnace | Implemented as a functional 9x3 chest fallback using the five real machine slots. Progress artwork is not reproduced. |
| Illager Invasion Imbuing Table | Implemented as a functional 9x3 chest fallback using the four real machine slots. Error-state artwork is not reproduced. |
| Custom menu artwork/text glyphs | Java text-glyph technique is not portable to Bedrock. A Bedrock-specific screen/form or resource-pack UI implementation would be required per menu family. |
| Recipe book repair | Partial: unreadable recipe displays are filtered; full custom recipe presentation remains open. |
| Effect notices | Implemented as acquire/expire action-bar notices while the protocol-visible icon uses a conservative vanilla substitute. |
| Ability meters and air-mining feedback | Not ported generically: these need per-mod gameplay state and would be misleading without a verified state source. |

### 4. Medium-to-high risk: generic entities

| Polymer Patcher++ feature | Hydraulic status |
| --- | --- |
| Spawn substitution and unreadable metadata protection | Implemented. |
| Vanilla model-layer capture | Implemented through the client geometry dump. |
| Citadel/Alex's Mobs capture | Implemented broadly, including animation sampling and Murmur support. |
| GeckoLib entities | Implemented from packaged `geo`/`geckolib/models` geometry and animation JSON, including keyframe unwrapping and unique geometry names. Renderer-only mappings still need a client dump. |
| GeckoLib block/item renderers | Partial. Ordinary resource models convert; code-only block and item renderer geometry has no generic Bedrock capture path. |
| Flat entities, hitbox/placeholders and held-player renderers | Partial; generic vanilla stand-ins exist, specialized flat/held-player rendering remains open. |
| Tracked-data renumbering | Hydraulic drops unsafe metadata for substituted types instead; faithful custom metadata mapping remains open. |

### 5. High risk: mod-specific behavior

| Mod | Polymer Patcher++ special handling not yet equivalent in Hydraulic |
| --- | --- |
| Enderscape | Palette safety, custom-payload safety, static custom shields, and readable fallbacks for magnia/mirror/rubble-shield code properties are covered. Per-stack rubble material, active End Haven shader, and client-only screen/physics effects cannot be reproduced generically. |
| Alex's Caves | Registry/biome codec safety, cave-painting models, Citadel entities, ordinary armor, map-marker safety, and a functional Nuclear Furnace are covered. Custom biome shaders, code-only armor shapes, and bespoke magnetic/raygun/spear/resistor/spirit visuals remain client-code effects. |
| Alex's Mobs | Rocky Chestplate rolling presentation, void-portal render rules, capsid showcase, and some special held/entity transforms. Citadel mobs and Murmur have broad Hydraulic support already. |
| Borrowed Echo | Player-skin substitution and renderer-specific entity behavior. |
| Neverend | Diving-suit model, floating text, and client-only effects/animations. |
| Sculk Horde | Client-only effect replay. |
| Illager Invasion | The Imbuing Table has an explicit functional chest layout. Its Java font-glyph artwork is not portable to Bedrock UI JSON without shipping a version-specific global UI override. |
| Yazz's Dungeons: Sequel | Twisted Shrieker renderer geometry and Wraithlight Lantern state model. |
| Subtly Dungeons | Its modded enchantment conditions are now sanitized from Bedrock registry data. Polymer's early consume-effect registration remains a Java item-numbering issue and does not apply to Geyser. |
| Voxy | Polymer carrier identity for distant terrain is Java/Polymer-specific and not portable to Bedrock. |

## Reviewed Polymer-only patches

These were inspected and intentionally not copied because the underlying mismatch does not exist on
the Geyser/Bedrock path:

- Carrier pools/twins, display-block repair, hidden-shape clicks, destroy-stage deduplication, Jade
  carriers, portal chunk repair, and Voxy fingerprints operate on Polymer's substituted Java blocks.
  Hydraulic registers Bedrock custom blocks and sends their real server state instead.
- Native item/component numbering, packet-decoder remapping, known-pack negotiation, companion-client
  manifests, and mismatched-mod rejection exist to make differently modded Java registries agree.
  A Bedrock client never loads those Java registries; Geyser owns that translation boundary.
- Java command-argument serialization, tooltip entity codecs, and Polymer recipe-book reconstruction
  target a vanilla Java client's codecs and UI. Hydraulic retains Geyser's command and recipe
  translation, filtering only entries known to be unreadable.
- Resource-pack splitting addresses Java's single-pack size cap. Hydraulic already registers one
  independently cached Bedrock pack per source mod, so there is no monolithic pack to split.
- Polymer's display entities, font glyph screens, held-player renderers, and carrier-side lighting
  cannot be reused by Bedrock. Equivalent behavior must be expressed as Bedrock geometry, attachables,
  particles, action-bar text, or a vanilla inventory, which is what the implemented fallbacks use.

The remaining mod-specific effects are not protocol fixes that can be copied safely: they replay a
particular mod's client tick/render code and require ongoing version-specific state probes. Hydraulic
now keeps their packets readable and their core objects visible; claiming their shaders, bespoke HUDs,
or every client-only particle as supported requires a real Bedrock implementation and visual test for
each effect.

## Evidence policy

- Unit tests prove format rewrites and other pure logic.
- A successful Gradle build proves compilation, mixin descriptor generation, service metadata, and packaging only.
- A server startup proves mixins bind and conversion executes for installed mods.
- Only a real Bedrock session plus screenshots/logs proves final rendering or interaction. Features are not
  marked visually flawless from compilation alone.
