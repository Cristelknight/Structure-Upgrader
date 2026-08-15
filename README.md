# Structure Upgrader | Warning: FULLY AI GENERATED!!!

Structure Upgrader is a server-side Fabric mod for Minecraft Java Edition 26.2. It safely upgrades folders of compressed structure `.nbt` files with Mojang's own DataFixerUpper pipeline.

This release upgrades older structures **to 26.2 only**. It includes Mojang's migrations through 26.2, including component changes introduced after 1.21.1, and it will never downgrade a newer structure.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API
- Java 25

The mod is required only on the logical server. Install it on a dedicated server or the client hosting a single-player world.

## Commands

Commands require the server-owner permission level. Paths are relative to the game/server directory and may point to one `.nbt` file or a directory.

```text
/structureupgrader scan <path>
/structureupgrader upgrade <path>
/structureupgrader upgrade assume <dataVersion> <path>
/structureupgrader repair scan <path>
/structureupgrader repair apply <path>
/structureupgrader status
/structureupgrader cancel
```

`scan` reads every structure and reports what would happen without changing files. `upgrade` processes all `.nbt` files recursively. The `assume` form supplies a source data version only to files which do not contain `DataVersion`; existing values are always honored.

`repair scan` reports known non-migration problems without changing files. `repair apply` fixes them with the same backup and atomic replacement protections as an upgrade. Repairs never run DataFixerUpper or change `DataVersion`, so repair and upgrade may be run in either order.

The initial repair rules remove exact `forge:entity_gravity` entries from old and new entity attribute lists and remove malformed root entity entries which have no valid entity ID. Other vanilla, modded, and unknown data is preserved.

For example, to upgrade folder-based datapack structures in a world named `world`:

```text
/structureupgrader scan world/datapacks/my_pack/data
/structureupgrader upgrade world/datapacks/my_pack/data
```

Run `reload` afterwards if the upgraded structures belong to a currently loaded datapack.

## Safety and output

- Absolute paths, paths outside the game directory, the game directory itself, backup directories, and symlink escapes are rejected.
- Only regular files ending in `.nbt` are considered, and directory symlinks are not followed.
- Versionless files are skipped unless `assume` is used. Files from a newer Minecraft version are skipped.
- Each output is written and verified in a sibling temporary file before replacement.
- The exact original is copied to `config/structure-upgrader/backups/<run-id>/` before an atomic replacement is attempted.
- A machine-readable report is written into the backup run, or to `config/structure-upgrader/reports/` for scans.
- A broken file does not stop the rest of the batch.

DataFixerUpper migrates vanilla data described by Minecraft's structure schema, including palettes, entities, block entities, and nested item stacks. Unknown modded tags are normally preserved, but this mod cannot supply migrations owned by a third-party mod that is not present in Mojang's fixer.

Minecraft may log `Block-attached entity at invalid position` while placing structures containing paintings or item frames. Vanilla stores their attachment coordinate as an absolute world position but does not transform that value before initially loading a structure entity. It subsequently snaps the entity to the transformed placement position, so Structure Upgrader preserves this data rather than deleting the entity. Avoiding that log message requires a runtime fix in the mod placing the structure and cannot be encoded in a reusable structure file.

## License

CC0-1.0
