package de.cristelknight.structureupgrader.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class StructureNbtValidator {
	private StructureNbtValidator() {
	}

	public static boolean isStructure(CompoundTag tag) {
		if (!tag.contains("size", Tag.TAG_LIST)
			|| !tag.contains("blocks", Tag.TAG_LIST)
			|| !tag.contains("entities", Tag.TAG_LIST)) {
			return false;
		}

		if (!tag.contains("palette", Tag.TAG_LIST) && !tag.contains("palettes", Tag.TAG_LIST)) {
			return false;
		}

		return tag.getList("size", Tag.TAG_INT).size() == 3;
	}
}
