package de.cristelknight.structureupgrader.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureRepairerTest {
	@Test
	void removesOldAndNewForgeGravityRecursivelyAndInvalidEntities() {
		CompoundTag structure = repairableStructure();
		structure.putInt("DataVersion", 3700);

		List<RepairAction> actions = StructureRepairer.repair(structure);

		assertEquals(List.of(
			new RepairAction(StructureRepairer.FORGE_ENTITY_GRAVITY_RULE, 2),
			new RepairAction(StructureRepairer.INVALID_STRUCTURE_ENTITY_RULE, 1)
		), actions);
		assertEquals(3700, structure.getIntOr("DataVersion", -1));

		ListTag entities = structure.getListOrEmpty("entities");
		assertEquals(2, entities.size());
		CompoundTag wolf = entities.getCompoundOrEmpty(0).getCompoundOrEmpty("nbt");
		ListTag attributes = wolf.getListOrEmpty("Attributes");
		assertEquals(1, attributes.size());
		assertEquals("minecraft:generic.max_health", attributes.getCompoundOrEmpty(0).getStringOr("Name", ""));

		CompoundTag passenger = wolf.getListOrEmpty("Passengers").getCompoundOrEmpty(0);
		ListTag passengerAttributes = passenger.getListOrEmpty("attributes");
		assertEquals(1, passengerAttributes.size());
		assertEquals("example:custom_attribute", passengerAttributes.getCompoundOrEmpty(0).getStringOr("id", ""));
	}

	@Test
	void leavesAttachedEntityDataUntouchedAndIsIdempotent() {
		CompoundTag structure = repairableStructure();
		CompoundTag paintingBefore = structure.getListOrEmpty("entities").getCompoundOrEmpty(1).copy();

		StructureRepairer.repair(structure);

		assertEquals(paintingBefore, structure.getListOrEmpty("entities").getCompoundOrEmpty(1));
		assertEquals(List.of(), StructureRepairer.repair(structure));
	}

	static CompoundTag repairableStructure() {
		CompoundTag structure = StructureNbtValidatorTest.structureTag();

		CompoundTag oldGravity = new CompoundTag();
		oldGravity.putString("Name", "forge:entity_gravity");
		oldGravity.putDouble("Base", 0.08D);
		CompoundTag maxHealth = new CompoundTag();
		maxHealth.putString("Name", "minecraft:generic.max_health");
		maxHealth.putDouble("Base", 40.0D);
		ListTag oldAttributes = new ListTag();
		oldAttributes.add(oldGravity);
		oldAttributes.add(maxHealth);

		CompoundTag newGravity = new CompoundTag();
		newGravity.putString("id", "forge:entity_gravity");
		newGravity.putDouble("base", 0.08D);
		CompoundTag customAttribute = new CompoundTag();
		customAttribute.putString("id", "example:custom_attribute");
		customAttribute.putDouble("base", 3.0D);
		ListTag newAttributes = new ListTag();
		newAttributes.add(newGravity);
		newAttributes.add(customAttribute);

		CompoundTag passenger = new CompoundTag();
		passenger.putString("id", "minecraft:villager");
		passenger.put("attributes", newAttributes);
		ListTag passengers = new ListTag();
		passengers.add(passenger);

		CompoundTag wolf = new CompoundTag();
		wolf.putString("id", "minecraft:wolf");
		wolf.put("Attributes", oldAttributes);
		wolf.put("Passengers", passengers);
		CompoundTag wolfWrapper = new CompoundTag();
		wolfWrapper.put("nbt", wolf);

		CompoundTag painting = new CompoundTag();
		painting.putString("id", "minecraft:painting");
		painting.put("block_pos", new IntArrayTag(new int[] {-283, -56, 31}));
		CompoundTag paintingWrapper = new CompoundTag();
		paintingWrapper.put("nbt", painting);

		ListTag entities = new ListTag();
		entities.add(wolfWrapper);
		entities.add(paintingWrapper);
		entities.add(new CompoundTag());
		structure.put("entities", entities);
		return structure;
	}
}
