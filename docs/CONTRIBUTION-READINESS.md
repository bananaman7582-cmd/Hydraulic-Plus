# 26.2 maintenance changes: contribution handoff

This is a local engineering record, not a GitHub PR description. The human contributor must write
the GitHub discussion and PR description in their own words and understand the implementation.

Policy reference: [GeyserMC AI policy, pinned revision](https://github.com/GeyserMC/.github/blob/c50dae6dffb17342ee02ed98a110467209f85d42/AI_POLICY.md).

## AI assistance and human requirements

- OpenAI Codex was used to inspect code and logs, investigate failures, implement changes,
  write automated tests, run local checks, and prepare this local handoff.
- The contributor must disclose the tool and its purposes in their own words when submitting.
- A human must test the changes before opening a PR, including a real Bedrock connection.
  Automated tests and the previous failed join attempts do not satisfy this requirement.
- Human testing of this build is pending. Do not claim successful Bedrock login or measured
  production memory improvement until that testing has happened.
- Keep upstream PRs focused. Submit the pack lifecycle/memory changes separately from the palette
  and recipe compatibility changes, and exclude unrelated fork features and formatting changes.
- No generated image, video, or audio assets are included. No GitHub discussion or PR was posted.

## Changes and rationale

- Block declarations, permutation conditions and Java-state mappings now share one supported
  property list. Every property with more than 16 values is collapsed, including blocks with
  multiple oversized counters. Counter-only blocks use base components rather than an empty
  permutation condition. Duplicate collapsed appearances are skipped before component creation.
- Geyser's recipe comparator now orders empty slots before nonempty descriptors. Existing ordering
  between nonempty descriptors is retained. This addresses the archived exception comparing
  `InvalidDescriptor` with `enderscape:celestial_planks`, without removing all custom-item recipes.
- The earlier artificial entity-flags workaround was removed. Native encoder/decoder tests show
  that empty entity metadata is correctly terminated and can be read by MCProtocolLib.
- A decoder diagnostic preserves the packet id, class and first failure stack, with at most 16
  distinct packet ids reported per connection. The old repeated four-byte decoder failure is not
  reproduced by the standalone codec tests and remains an end-to-end validation requirement.
- Model parent lookup uses an index containing models only, preserving first-mod precedence and
  vanilla fallback. It no longer retains complete source packs (including textures and sounds).
- Conversion worker count is limited by processor count and heap budget (one to four workers).
  The executor belongs to the listener rather than a static executor that stays shut down after
  a previous server instance. Pack registration happens on the event's owning thread.
- Geometry timestamps are inspected once per resource-pack load, rather than once per mod.
- Missing or malformed cached manifests cause regeneration. Pack publication uses a temporary
  sibling file so failed exports retain the last complete pack. An old output file no longer makes
  a failed or empty export report success.
- Ordered source hashing no longer submits parallel work into the shared common pool.
- The follow-up comparison uses Polymer Patcher++ 0.14.356. Its full prioritized inventory and
  cross-subsystem limitations are recorded in COMPATIBILITY-AUDIT-2026-10-19.md.
- Geyser/Floodgate audience checks are shared by packet, menu, interaction and Polymer hooks,
  including linked accounts. Nested packet bundles now omit rejected payloads and null particles
  without discarding readable neighbours. Null particles produce a bounded origin diagnostic.
- Vanilla entity metadata is mapped back from shifted live accessors to original wire indices,
  checking serializer identity. Substituted mobs keep only universal fields; an optional Patcher
  hook prevents its encoding pass from applying the correction a second time.
- Custom sounds attached to real or virtual entities are now inlined, retaining id/source/seed.
  Vanilla entity classes are recovered from type declarations rather than constructing dummy mobs.
- Custom Java block states now register their collision boxes instead of empty arrays. Multipart
  predicates support nested AND/OR and pipe-separated alternatives, with serialized property names.
  Complete multipart model composition remains open.
- Slot-only non-grid machines can use a compact chest layout. Outside/drag-boundary clicks and the
  original machine broadcast hook are preserved. Unsupported buttons/ambiguous slots remain rejected.
- Displayed block items are removed on chunk unload, section updates and respawn. Their update path
  also runs for packets inside bundles. Per-viewer entity/name/effect state is reset on respawn.
- Animation sizing now matches Minecraft's horizontal-strip and one-specified-dimension semantics.

## Verification and known limits

Local verification on 2026-10-19: the full 26.2 build and data generation succeeded, and all
60 automated tests passed. The final recipe hook uses class-based Mixin selectors because jar
shading only partly relocated the equivalent string descriptor; the packaged selector is checked
against the installed Geyser class signature before staging.

Run `./gradlew build --offline` with Java 25 when the dependencies are cached, or `./gradlew build`
with repository access. The shared tests cover existing animation/resource/menu behavior plus
block-property projection, cache corruption, failed/complete publication, worker limits, model
precedence, recipe sorting, native packet encoding and Geyser mixin target compatibility.
New coverage includes the full vanilla entity declaration/layout inventory (>150 entity types),
metadata index/type rejection, custom sound decoding through MCProtocolLib, nested bundle filtering,
linked audience checks, collision centre/dimension conversion, recursive block predicates, actual
menu facade delegation and extra animation dimension cases. Installed Patcher 0.14.356 was checked
for the optional metadata hook's exact method signature. This is not a runtime two-mod mixin test.

The archived failing run is `2026-10-11-5.log.gz`; current `latest.log` has been replaced by a later
Java-player session with Hydraulic and Geyser disabled. Neither log proves the current fixes work
end to end. The reported 109% memory reading has no accompanying heap profile; process memory can
include native allocations beyond `-Xmx`. A lower conversion worker limit is a bound on concurrent
work, not a guarantee about total modpack memory usage.

## Human test checklist and regression risks

- Start the same Minecraft 26.2 modpack with Geyser and Hydraulic enabled, join from Bedrock, and
  confirm Locating Server completes. Retain the fresh full log if a decoder diagnostic appears.
- Check grass, vines, Enderscape blocks and a counter-only block. Oversized-property collapse still
  loses appearances controlled only by the omitted counter; multi-counter blocks need visual QA.
- Open the recipe book and craft a custom shaped recipe containing empty slots and a tag ingredient.
  Verify ingredient positions, recipe grouping and manual crafting on both Bedrock and Java.
- Compare startup time and peak heap/process memory before and after, including a cached second
  start. Reduced conversion concurrency may increase first-start conversion time.
- Exercise entity spawning/removal and flags (invisibility, fire, glowing), including Polymer's
  virtual entities. Standalone wire tests do not exercise every mod's encoding-time mixins.
- With Hydraulic and Patcher together, test vanilla player health/skin layers/pose, vanilla mobs,
  synthetic entities and linked accounts. Confirm metadata is not renumbered twice. Unknown layouts
  deliberately omit metadata instead of guessing; missing fields are a possible visible regression.
- Walk/jump against custom slabs, stairs and connected shapes. Java collision boxes are now present,
  but neighbour-sensitive sampling and Bedrock's simpler collision geometry are still approximate.
- Verify compact machine menus with pickup, shift-click, hotbar swap, dragging, dropping outside,
  output restrictions, carried items on close and custom menu tick updates. No inventory-duplication
  or complete arbitrary-GUI support claim is made from the fixture tests.
- Check horizontal/one-dimension animations, attached mob sounds, chunk travel and dimension changes
  for stale displayed items. Atlas flipbooks do not prove animated armor/entity skins.
- Inspect generated pack ZIPs and verify a second start reuses them. Test a corrupt cached manifest.
  Unreadable geometry directories deliberately force regeneration and emit a warning.
- Check overlapping textures/models across mods; indexed model lookup should retain old priority,
  while serialized pack registration can expose existing pack-order assumptions.
- Restart an integrated server in the same JVM. The conversion executor now has the right lifecycle,
  but unrelated static registries and caches still prevent a claim of complete reload support.
- Check the final shaded mixin descriptors against the intended Geyser release. Internal Geyser
  class/method changes remain an upgrade risk.

## Deployment

Target: the Fork server's `ACS SMP 2026-SEP/mods` directory, Minecraft 26.2 only. If Geyser is disabled
or the server is running, stage the jar with a `.pending` suffix; do not silently re-enable disabled
mods or replace a live jar. This task does not publish GitHub builds or produce a 26.3 build.

Latest staged artifact: `hydraulic-plus-2026.10.19+26.2.compat.1.jar.pending` (2,701,181 bytes).
SHA-256: `E27D079EE17F24DC40214292E1E910685FFD6790EDAB1AA3458CD600C213BF13`.
The source and staged copies match. The server was running and Geyser was disabled, so the artifact
was not activated. Human Bedrock validation and peak-memory profiling remain pending.
The previous `hydraulic-plus-2026.10.19+26.2.jar.pending` was preserved. The final jar contains the
new helpers, optional Patcher hook, correctly shaded recipe selector, and all ten pack-module service
entries. No Patcher classes are bundled. No GitHub build, release, upload or 26.3 build was made.
