# Compatibility engineering audit — Minecraft 26.2

## Scope and evidence

This is a local engineering record, not an upstream PR description or a declaration of full mod
support. It extends the earlier maintenance work rather than replacing it. The source comparison is
against `Polymer Patcher++/gradle.properties` version **0.14.356**, not the old 0.14.337 inventory.
Its compatibility registration, packet mixins, resource/model fallback engines, entity presentation,
menu adapters, and dedicated compatibility modules were inspected. Hydraulic was reviewed across
the subsystem boundaries below. This is a targeted architectural/code audit, not a claim that every
line or every installed mod has been exercised.

Polymer Patcher targets Java clients. Hydraulic targets Bedrock through Geyser. Java display-item
carriers, companion protocols, font-glyph menus and client shaders are not portable implementations
of Bedrock geometry, menus or effects. No Polymer Patcher classes/assets were bundled into Hydraulic.

Evidence levels:

- **Code/tested:** implemented and covered by local automated tests.
- **Existing/partial:** an existing conversion path exists; fidelity still requires client testing.
- **Open:** no faithful equivalent is claimed.
- A matching method descriptor is not proof that two mods' mixins compose at runtime.
- No successful Bedrock login, visual fidelity or production memory reduction is claimed here.

## Cross-subsystem findings, easiest first

| Area | Review result and action | Remaining limitation |
| --- | --- | --- |
| Fabric bootstrap and audience | Unified Geyser/Floodgate checks across registry sync, Polymer block/item mapping, menus, arm swings and Murmur handling. Linked Java UUIDs are no longer excluded by UUID-only checks. | Linked-account login still needs a real session test. |
| Configuration and Polymer separation | Optional mappings still respect `hidePolymerFromBedrock`; Patcher metadata coexistence is independently enabled when Patcher is installed because Hydraulic always normalizes Bedrock metadata. | Custom mod payloads remain blocked even if Java-style Polymer mappings are opted into. The option is not permission to execute a Java companion protocol on Bedrock. |
| Outbound packet boundary | One send wrapper handles rejection and rewriting; recursive bundles omit rejected leaves, retain order and preserve readable neighbours. Null-particle packets are dropped with one origin diagnostic. | Other mods can still alter packets later during encoding. The earlier unidentified four-byte decoder failure needs the fresh diagnostic from an actual join. |
| Sounds | Both positional and entity-attached custom sounds use inline holders. Attached sounds preserve the original entity id, including synthetic entities, without constructing dummy entities. | Pack sound conversion/mapping and actual audibility need client verification. |
| Blocks: palette and state | Earlier >16-value property projection remains. Nested multipart AND/OR, `north|south` alternatives and serialized property names are now handled. | Only the first matching model is chosen; full multipart geometry composition and weighted variation remain open. Oversized counters deliberately lose appearances. |
| Blocks: collision | Removed the empty Java collision-array placeholder. Every registered Java state now supplies centre/size boxes from its collision shape, including multiple boxes. | Shape sampling uses a synthetic block view, with default-state fallback for exceptions. World/neighbour-dependent shapes and Bedrock's simpler client collision boxes remain approximate. |
| Block-entity item displays | Bundled block-entity updates now reach display handling. Displays are removed for section updates, chunk unloads and respawns; per-connection entity/name/effect state is reset on respawn. | Dropped-item presentation still bobs/rotates. Initial chunk-only NBT, arbitrary code-only renderers and exact showcase transforms are not solved. |
| Generic items/components | Reviewed item registration, component conversion, creative selection, model fallback, tool/equipment/repair rules and item translation hooks. Existing static defaults remain. | Alternate `ITEM_MODEL` ownership/cross-mod resources, arbitrary modded component codecs, per-stack tints/selectors and composite model layers need follow-up. Several old Geyser mixins are inactive scaffolding. |
| Shields/bows/crossbows/equipment | Existing attachables and standard component-based shield detection remain; this is generic registration, not a mod-name allowlist. | Dynamic rubble-shield materials, renderer-only shapes, trims/special armor transforms and exact per-stack held variants are not newly supported. |
| Animation/texture conversion | Matched Minecraft's own `AnimationMetadataSection.calculateFrameSize` semantics. Horizontal strips and width-only/height-only declarations now convert correctly. Existing frame-order, duration and flipbook tests remain. | Atlas flipbooks do not prove animated entity skins/armor. Very long timelines are still bounded/approximated; interpolated visual timing needs a real client. |
| Menus | Retained known Nuclear Furnace and Imbuing layouts. Added compact fallback for slot-only non-grid machines (1–54 machine slots plus 36 player slots). Original slot identity, outside/drag boundary clicks and menu broadcast hooks are preserved. | No arbitrary buttons, client logic, progress artwork, overlaid/ambiguous/inactive/fake slots or menu artwork is claimed. Live shift-click/drag/output-slot regression testing remains required. |
| Vanilla entity metadata | Recovered original accessor order and serializer identity from Minecraft class bytes; mapped live shifted indices back to vanilla wire indices. Tests cover all declared vanilla entity types (>150), shifted fields, wrong serializers and unknown fields. | Unrecognized layouts fail closed by omitting metadata. Synthetic entities need their spawn type observed; unresolved entities keep only base Entity fields. |
| Modded entities | Existing spawn substitution, model/animation resources, GeckoLib JSON and client/Citadel dumps remain. Universal Entity fields are preserved with safe indices; type-specific fields on heuristic stand-ins remain excluded. | Babies, variants, transforms, special render passes and custom tracked state need explicit Bedrock properties. No new renderer capture backend was added. |
| Patcher coexistence | Installed 0.14.356 exposes the expected three-argument `VanillaEntityData.sanitize`. A Bedrock-only optional hook skips its second metadata renumbering after Hydraulic's pass. Java recipients retain Patcher's behavior. | Encoding-time compatibility must be tested with both mods enabled; `require=0` is intentionally optional, not a guarantee for future Patcher versions. |
| Entity lookup cost | Vanilla type declarations now provide classes without constructing/discarding every vanilla mob on the first custom spawn. | Static ancestry and animation caches still do not support arbitrary same-JVM registry reloads. |
| Particles/effects/maps | Reviewed resource definitions and packet-safe vanilla lookalikes/markers. Existing effect notices remain. | Custom particle definitions alone are not emitted as those effects. Client-only trails, shaders and faithful map-marker art remain separate work. |
| Resource lifecycle/storage | Earlier bounded workers, model-only index, ordered registration, atomic publication and corrupt-manifest regeneration remain tested. | Corrupt `materials.json`, heterogeneous key equality/hash contracts, global module ordering/thread safety and broad exception suppression deserve separate focused patches. No memory profile is available. |
| Platform/build/CI | Fabric 26.2 is the only build/deployment target. Test fixture/data generation and shaded packaging are verified locally. | NeoForge is disabled and untested. Snapshot/internal APIs, mutable CI action references and source/version-specific mixin targets remain risks. No GitHub publication is authorized. |

## Dedicated Polymer Patcher compatibility inventory

These rows record real distinctions, not blanket claims that installing any mod in the family is
flawless. The local `CompatibilityModules` and dedicated packages provide the comparison boundary.

| Module | Existing Hydraulic path | Missing equivalent / next investigation |
| --- | --- | --- |
| Enderscape | Custom blocks/items, projected palette properties, static component-based shields, safe recipes/payloads and resource fallbacks. | Dynamic rubble material/dye state, magnia/mirror active presentation and End Haven/client effects. |
| Illager Invasion | Explicit functional Imbuing Table slot layout. | Error/progress artwork and mod-specific feedback; verify result extraction and cost updates live. |
| Alex's Caves | Generic/Citadel entities, standard equipment, safe registries/maps, functional Nuclear Furnace slots. | Stack identity/tints, bespoke armor, submarines, ghosts/slimes/held transforms, cave lighting, raygun/magnetic/spirit/extinction/resistor effects, clouds and sirens. |
| Alex's Mobs | Existing Citadel captures/animation sampling and Murmur workaround. | Rocky rolling armor, special render/held rules, capsid showcase and carver differences. |
| Neverend (`liminalpools`) | Generic entities/resources and ordinary equipment fallbacks. | Diving suit, floating text, semantic portal/window presentation, special render brightness and client effects. |
| Borrowed Echo | Generic captured model/vanilla stand-in. | Disguise-dependent skins/models, passive-carrier transitions, slim/wide player rendering and neck/glitch/rupture poses. |
| Sculk Horde | Generic captured/resource geometry and safe particle fallback. | Client-only particle replay and renderer-only block geometry. Patcher's own GeoRenderState API errors in the server log are not evidence of working Hydraulic geometry. |
| Yazz's Dungeons: Sequel (`ydtwo`) | Generic resource conversion. | Twisted Shrieker code-rendered model and Wraithlight Lantern soul-state presentation. |
| Cracker's Wither Storm | Generic registry/packet protections; bundled custom payloads now blocked consistently. | Beacon crystals/beams and mod-specific dimension, entity-state, tractor and client-packet effects. The mod's Java payload handlers are not Bedrock support. |
| Subtly Dungeons | Existing modded enchantment sanitization. | Its early consume-effect registration is a server initialization issue, already handled when Patcher is installed; isolated Hydraulic-only behavior needs reproduction before declaring it irrelevant or adding a duplicate hook. |
| Voxy | Not applicable as a Bedrock distant-rendering backend. | Patcher's Java carrier identity patch cannot provide Voxy rendering on Bedrock. |
| `mcddnether`, `variantsandventures` directories | No dedicated Java implementations found in these empty compatibility directories. | Directory names are not treated as proof of special support; generic paths only. |

## Ordered remaining work

1. **Validation first:** enable the staged 26.2 build in a controlled server test with Geyser and
   Patcher; capture a successful Bedrock join or the new packet diagnostic. Measure heap and total
   process memory during fresh conversion and cached startup. Do not infer success from unit tests.
2. **Medium:** cross-mod/alternate item-model ownership, per-stack model/texture selection, full
   multipart block composition, material-cache corruption recovery. Use synthetic packs and codec
   tests before introducing mod-specific rules.
3. **Medium/high:** extend the client dump format to block/item/armor renderer geometry, with explicit
   model-to-registry ownership, textures, transforms and version stamps. This is the reusable route
   for YD2/Sculk Horde/code-only shields rather than a growing list of guessed texture names.
4. **High:** per-entity presentation state for Borrowed Echo disguises, Neverend equipment, Alex's
   Caves special models and Rocky rolling; each needs a stable server state source and Bedrock QA.
5. **Highest:** bounded per-viewer replay of mod-specific beams/particles/HUDs and Wither Storm
   effects. Java shaders and physics do not acquire a faithful Bedrock equivalent automatically.

## Tests and human handoff

The expanded shared suite contains **60 tests** with no failures/errors as of this batch. It covers
bundle filtering, audience checks, real native-to-MCProtocolLib sound decoding, the complete vanilla
entity declaration/layout inventory, metadata remapping/type rejection, collision coordinate
conversion, recursive predicates, menu redirection, frame sizing, and the earlier maintenance cases.
Full build/packaging and final artifact checks are recorded in CONTRIBUTION-READINESS.md.

Highest regression risks: two mods' encoding-time mixins, vanilla player metadata shifted by mods,
synthetic display entities, collision-dependent movement, dynamic machine slots, drag/shift-click and
output handling, resource priority, and first-start conversion memory. Tests use controlled fixtures;
they do not launch a full modpack or supply Bedrock screenshots.

Follow the pinned GeyserMC AI policy referenced in CONTRIBUTION-READINESS.md. Human review, human
testing, disclosure, and a human-written focused PR description are still required before submission.
