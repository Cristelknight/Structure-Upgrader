package de.cristelknight.structureupgrader.upgrade;

public enum UpgradeMode {
	SCAN,
	UPGRADE,
	REPAIR_SCAN,
	REPAIR;

	public boolean isRepair() {
		return this == REPAIR_SCAN || this == REPAIR;
	}

	public boolean isDryRun() {
		return this == SCAN || this == REPAIR_SCAN;
	}

	public boolean writesFiles() {
		return this == UPGRADE || this == REPAIR;
	}
}
