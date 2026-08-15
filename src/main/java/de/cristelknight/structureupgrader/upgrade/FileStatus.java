package de.cristelknight.structureupgrader.upgrade;

public enum FileStatus {
	UPGRADED,
	WOULD_UPGRADE,
	REPAIRED,
	WOULD_REPAIR,
	NO_REPAIRS,
	CURRENT,
	FUTURE_VERSION,
	MISSING_VERSION,
	NOT_A_STRUCTURE,
	FAILED
}
