package de.cristelknight.structureupgrader.upgrade;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureUpgradeServiceTest {
	private static final int DATA_VERSION_1_20_4 = 3700;
	private static final int DATA_VERSION_1_21_1 = 3955;
	private static final int DATA_VERSION_26_2 = 4903;
	private static final StructureUpgradeService.JobControl NEVER_CANCELLED = new StructureUpgradeService.JobControl() {
		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public void onProgress(int processed, int total) {
		}
	};

	@TempDir
	Path serverDirectory;

	@BeforeAll
	static void detectVersion() {
		SharedConstants.tryDetectVersion();
		assertEquals(DATA_VERSION_26_2, SharedConstants.getCurrentVersion().dataVersion().version());
	}

	@Test
	void upgradesAndKeepsAnExactBackup() throws IOException {
		Path source = Files.createDirectories(serverDirectory.resolve("structures")).resolve("house.nbt");
		CompoundTag structure = StructureNbtValidatorTest.structureTag();
		structure.putInt("DataVersion", DATA_VERSION_1_20_4);
		NbtIo.writeCompressed(structure, source);
		byte[] original = Files.readAllBytes(source);

		StructureUpgradeService service = new StructureUpgradeService(noOpFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("test-run", UpgradeMode.UPGRADE, "structures", source.getParent(), null, NEVER_CANCELLED);

		assertEquals(1, result.report().upgraded());
		assertEquals(DATA_VERSION_26_2, read(source).getIntOr("DataVersion", -1));
		Path backup = serverDirectory.resolve("config/structure-upgrader/backups/test-run/structures/house.nbt");
		assertArrayEquals(original, Files.readAllBytes(backup));
		assertTrue(Files.isRegularFile(result.reportPath()));
	}

	@Test
	void skipsMissingAndFutureVersionsWithoutChangingThem() throws IOException {
		Path directory = Files.createDirectories(serverDirectory.resolve("structures"));
		Path missing = directory.resolve("missing.nbt");
		Path future = directory.resolve("future.nbt");
		NbtIo.writeCompressed(StructureNbtValidatorTest.structureTag(), missing);
		CompoundTag futureTag = StructureNbtValidatorTest.structureTag();
		futureTag.putInt("DataVersion", DATA_VERSION_26_2 + 1);
		NbtIo.writeCompressed(futureTag, future);
		byte[] missingOriginal = Files.readAllBytes(missing);
		byte[] futureOriginal = Files.readAllBytes(future);

		StructureUpgradeService service = new StructureUpgradeService(noOpFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("skip-run", UpgradeMode.UPGRADE, "structures", directory, null, NEVER_CANCELLED);

		assertEquals(2, result.report().skipped());
		assertArrayEquals(missingOriginal, Files.readAllBytes(missing));
		assertArrayEquals(futureOriginal, Files.readAllBytes(future));
		assertFalse(Files.exists(serverDirectory.resolve("config/structure-upgrader/backups/skip-run/structures/missing.nbt")));
	}

	@Test
	void mojangDataFixerConvertsNestedLegacyItemDataToComponents() throws IOException {
		Path source = Files.createDirectories(serverDirectory.resolve("structures")).resolve("chest.nbt");
		NbtIo.writeCompressed(legacyChestStructure(), source);

		StructureUpgradeService service = new StructureUpgradeService(DataFixers.getDataFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("dfu-run", UpgradeMode.UPGRADE, "structures/chest.nbt", source, null, NEVER_CANCELLED);

		assertEquals(1, result.report().upgraded());
		CompoundTag upgraded = read(source);
		CompoundTag item = upgraded.getListOrEmpty("blocks").getCompoundOrEmpty(0)
			.getCompoundOrEmpty("nbt").getListOrEmpty("Items").getCompoundOrEmpty(0);
		assertTrue(item.getCompound("components").isPresent());
		assertTrue(item.getCompoundOrEmpty("components").contains("minecraft:damage"));
		assertFalse(item.contains("tag"));
	}

	@Test
	void mojangDataFixerConverts1211FoodComponentsTo262Shape() throws IOException {
		Path source = Files.createDirectories(serverDirectory.resolve("structures")).resolve("food_chest.nbt");
		NbtIo.writeCompressed(foodChestStructure(), source);

		StructureUpgradeService service = new StructureUpgradeService(DataFixers.getDataFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("food-dfu-run", UpgradeMode.UPGRADE, "structures/food_chest.nbt", source, null, NEVER_CANCELLED);

		assertEquals(1, result.report().upgraded());
		CompoundTag upgraded = read(source);
		CompoundTag item = upgraded.getListOrEmpty("blocks").getCompoundOrEmpty(0)
			.getCompoundOrEmpty("nbt").getListOrEmpty("Items").getCompoundOrEmpty(0);
		CompoundTag components = item.getCompoundOrEmpty("components");
		CompoundTag food = components.getCompoundOrEmpty("minecraft:food");
		CompoundTag consumable = components.getCompoundOrEmpty("minecraft:consumable");

		assertFalse(food.contains("eat_seconds"));
		assertFalse(food.contains("using_converts_to"));
		assertEquals(2.4F, consumable.getFloatOr("consume_seconds", -1.0F));
		assertEquals("minecraft:bowl", components.getCompoundOrEmpty("minecraft:use_remainder").getStringOr("id", ""));
		assertEquals(DATA_VERSION_26_2, upgraded.getIntOr("DataVersion", -1));
	}

	@Test
	void repairScanReportsActionsWithoutWritingOrBackingUp() throws IOException {
		Path source = Files.createDirectories(serverDirectory.resolve("structures")).resolve("repairable.nbt");
		CompoundTag structure = StructureRepairerTest.repairableStructure();
		structure.putInt("DataVersion", DATA_VERSION_1_20_4);
		NbtIo.writeCompressed(structure, source);
		byte[] original = Files.readAllBytes(source);

		StructureUpgradeService service = new StructureUpgradeService(noOpFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("repair-scan-run", UpgradeMode.REPAIR_SCAN, "structures", source.getParent(), null, NEVER_CANCELLED);

		assertEquals(1, result.report().wouldRepair());
		assertEquals(0, result.report().repaired());
		assertEquals(List.of(
			new RepairAction(StructureRepairer.FORGE_ENTITY_GRAVITY_RULE, 2),
			new RepairAction(StructureRepairer.INVALID_STRUCTURE_ENTITY_RULE, 1)
		), result.report().repairActions());
		assertArrayEquals(original, Files.readAllBytes(source));
		assertFalse(Files.exists(service.backupDirectory().resolve("repair-scan-run")));
		assertTrue(Files.isRegularFile(result.reportPath()));
	}

	@Test
	void repairApplyBacksUpPreservesVersionAndIsIdempotent() throws IOException {
		Path source = Files.createDirectories(serverDirectory.resolve("structures")).resolve("repairable.nbt");
		CompoundTag structure = StructureRepairerTest.repairableStructure();
		structure.putInt("DataVersion", DATA_VERSION_1_20_4);
		NbtIo.writeCompressed(structure, source);
		byte[] original = Files.readAllBytes(source);

		StructureUpgradeService service = new StructureUpgradeService(noOpFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("repair-run", UpgradeMode.REPAIR, "structures", source.getParent(), null, NEVER_CANCELLED);

		assertEquals(1, result.report().repaired());
		assertEquals(DATA_VERSION_1_20_4, read(source).getIntOr("DataVersion", -1));
		Path backup = serverDirectory.resolve("config/structure-upgrader/backups/repair-run/structures/repairable.nbt");
		assertArrayEquals(original, Files.readAllBytes(backup));
		assertTrue(Files.isRegularFile(result.reportPath()));

		UpgradeRunResult second = service.run("second-repair-run", UpgradeMode.REPAIR, "structures", source.getParent(), null, NEVER_CANCELLED);
		assertEquals(0, second.report().repaired());
		assertEquals(1, second.report().skipped());
		assertEquals(FileStatus.NO_REPAIRS, second.report().files().getFirst().status());
		assertFalse(Files.exists(service.backupDirectory().resolve("second-repair-run/structures/repairable.nbt")));
	}

	@Test
	void repairsVersionlessAndFutureStructuresWithoutChangingTheirVersions() throws IOException {
		Path directory = Files.createDirectories(serverDirectory.resolve("structures"));
		Path versionless = directory.resolve("versionless.nbt");
		Path future = directory.resolve("future.nbt");
		NbtIo.writeCompressed(StructureRepairerTest.repairableStructure(), versionless);
		CompoundTag futureStructure = StructureRepairerTest.repairableStructure();
		futureStructure.putInt("DataVersion", DATA_VERSION_26_2 + 100);
		NbtIo.writeCompressed(futureStructure, future);

		StructureUpgradeService service = new StructureUpgradeService(noOpFixer(), DATA_VERSION_26_2, serverDirectory);
		UpgradeRunResult result = service.run("mixed-version-repair", UpgradeMode.REPAIR, "structures", directory, null, NEVER_CANCELLED);

		assertEquals(2, result.report().repaired());
		assertFalse(read(versionless).contains("DataVersion"));
		assertEquals(DATA_VERSION_26_2 + 100, read(future).getIntOr("DataVersion", -1));
	}

	private static CompoundTag legacyChestStructure() {
		CompoundTag structure = StructureNbtValidatorTest.structureTag();
		structure.putInt("DataVersion", DATA_VERSION_1_20_4);

		CompoundTag paletteEntry = new CompoundTag();
		paletteEntry.putString("Name", "minecraft:chest");
		ListTag palette = new ListTag();
		palette.add(paletteEntry);
		structure.put("palette", palette);

		CompoundTag itemTag = new CompoundTag();
		itemTag.putInt("Damage", 5);
		CompoundTag item = new CompoundTag();
		item.put("Slot", ByteTag.valueOf((byte) 0));
		item.putString("id", "minecraft:diamond_sword");
		item.put("Count", ByteTag.valueOf((byte) 1));
		item.put("tag", itemTag);
		ListTag items = new ListTag();
		items.add(item);

		CompoundTag blockEntity = new CompoundTag();
		blockEntity.putString("id", "minecraft:chest");
		blockEntity.put("Items", items);
		CompoundTag block = new CompoundTag();
		ListTag position = new ListTag();
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		block.put("pos", position);
		block.putInt("state", 0);
		block.put("nbt", blockEntity);
		ListTag blocks = new ListTag();
		blocks.add(block);
		structure.put("blocks", blocks);
		return structure;
	}

	private static CompoundTag foodChestStructure() {
		CompoundTag structure = StructureNbtValidatorTest.structureTag();
		structure.putInt("DataVersion", DATA_VERSION_1_21_1);

		CompoundTag paletteEntry = new CompoundTag();
		paletteEntry.putString("Name", "minecraft:chest");
		ListTag palette = new ListTag();
		palette.add(paletteEntry);
		structure.put("palette", palette);

		CompoundTag remainder = new CompoundTag();
		remainder.putString("id", "minecraft:bowl");
		remainder.putInt("count", 1);
		CompoundTag food = new CompoundTag();
		food.putInt("nutrition", 4);
		food.putFloat("saturation", 0.6F);
		food.putFloat("eat_seconds", 2.4F);
		food.put("using_converts_to", remainder);
		CompoundTag components = new CompoundTag();
		components.put("minecraft:food", food);

		CompoundTag item = new CompoundTag();
		item.put("Slot", ByteTag.valueOf((byte) 0));
		item.putString("id", "minecraft:beetroot_soup");
		item.putInt("count", 1);
		item.put("components", components);
		ListTag items = new ListTag();
		items.add(item);

		CompoundTag blockEntity = new CompoundTag();
		blockEntity.putString("id", "minecraft:chest");
		blockEntity.put("Items", items);
		CompoundTag block = new CompoundTag();
		ListTag position = new ListTag();
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		position.add(IntTag.valueOf(0));
		block.put("pos", position);
		block.putInt("state", 0);
		block.put("nbt", blockEntity);
		ListTag blocks = new ListTag();
		blocks.add(block);
		structure.put("blocks", blocks);
		return structure;
	}

	private static CompoundTag read(Path path) throws IOException {
		return NbtIo.readCompressed(path, NbtAccounter.create(StructureUpgradeService.MAX_EXPANDED_NBT_BYTES));
	}

	private static DataFixer noOpFixer() {
		return new DataFixer() {
			@Override
			public <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion) {
				return input;
			}

			@Override
			public Schema getSchema(int key) {
				throw new UnsupportedOperationException();
			}
		};
	}
}
