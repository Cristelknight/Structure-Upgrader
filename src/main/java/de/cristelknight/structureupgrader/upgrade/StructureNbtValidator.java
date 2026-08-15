package de.cristelknight.structureupgrader.upgrade;

import net.minecraft.nbt.CompoundTag;

public final class StructureNbtValidator {
	private StructureNbtValidator() {
	}

	public static boolean isStructure(CompoundTag tag) {
		if (tag.getList("blocks").isEmpty() || tag.getList("entities").isEmpty()) {
			return false;
		}

		if (tag.getList("palette").isEmpty() && tag.getList("palettes").isEmpty()) {
			return false;
		}

		return tag.getList("size")
			.filter(size -> size.size() == 3
				&& size.getInt(0).isPresent()
				&& size.getInt(1).isPresent()
				&& size.getInt(2).isPresent())
			.isPresent();
	}
}
