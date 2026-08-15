package de.cristelknight.structureupgrader.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

public final class StructureRepairer {
	public static final String FORGE_ENTITY_GRAVITY_RULE = "remove_forge_entity_gravity";
	public static final String INVALID_STRUCTURE_ENTITY_RULE = "remove_invalid_structure_entities";
	private static final String FORGE_ENTITY_GRAVITY = "forge:entity_gravity";

	private StructureRepairer() {
	}

	public static List<RepairAction> repair(CompoundTag structure) {
		int gravityAttributes = removeForgeGravityRecursively(structure);
		int invalidEntities = removeInvalidStructureEntities(structure);
		List<RepairAction> actions = new ArrayList<>(2);
		if (gravityAttributes > 0) {
			actions.add(new RepairAction(FORGE_ENTITY_GRAVITY_RULE, gravityAttributes));
		}
		if (invalidEntities > 0) {
			actions.add(new RepairAction(INVALID_STRUCTURE_ENTITY_RULE, invalidEntities));
		}
		return List.copyOf(actions);
	}

	private static int removeForgeGravityRecursively(Tag tag) {
		int removed = 0;
		if (tag instanceof CompoundTag compound) {
			removed += removeAttribute(compound.getList("Attributes").orElse(null), "Name");
			removed += removeAttribute(compound.getList("attributes").orElse(null), "id");
			for (Tag child : List.copyOf(compound.values())) {
				removed += removeForgeGravityRecursively(child);
			}
		} else if (tag instanceof ListTag list) {
			for (Tag child : List.copyOf(list)) {
				removed += removeForgeGravityRecursively(child);
			}
		}
		return removed;
	}

	private static int removeAttribute(ListTag attributes, String idKey) {
		if (attributes == null) {
			return 0;
		}

		int removed = 0;
		for (int index = attributes.size() - 1; index >= 0; index--) {
			CompoundTag attribute = attributes.getCompound(index).orElse(null);
			if (attribute != null && FORGE_ENTITY_GRAVITY.equals(attribute.getStringOr(idKey, ""))) {
				attributes.remove(index);
				removed++;
			}
		}
		return removed;
	}

	private static int removeInvalidStructureEntities(CompoundTag structure) {
		ListTag entities = structure.getList("entities").orElse(null);
		if (entities == null) {
			return 0;
		}

		int removed = 0;
		for (int index = entities.size() - 1; index >= 0; index--) {
			CompoundTag wrapper = entities.getCompound(index).orElse(null);
			CompoundTag entity = wrapper == null ? null : wrapper.getCompound("nbt").orElse(null);
			String id = entity == null ? "" : entity.getStringOr("id", "");
			if (id.isBlank()) {
				entities.remove(index);
				removed++;
			}
		}
		return removed;
	}
}
