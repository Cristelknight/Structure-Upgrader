package de.cristelknight.structureupgrader.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureNbtValidatorTest {
	@Test
	void acceptsAStandardStructureRoot() {
		assertTrue(StructureNbtValidator.isStructure(structureTag()));
	}

	@Test
	void rejectsGenericNbtAndWrongSizeShape() {
		assertFalse(StructureNbtValidator.isStructure(new CompoundTag()));
		CompoundTag structure = structureTag();
		structure.put("size", new ListTag());
		assertFalse(StructureNbtValidator.isStructure(structure));
	}

	static CompoundTag structureTag() {
		CompoundTag tag = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		tag.put("size", size);
		tag.put("palette", new ListTag());
		tag.put("blocks", new ListTag());
		tag.put("entities", new ListTag());
		return tag;
	}
}
