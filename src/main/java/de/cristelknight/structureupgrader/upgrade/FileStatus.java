package de.cristelknight.structureupgrader.upgrade;

public enum FileStatus {
	UPGRADED,
	WOULD_UPGRADE,
	CURRENT,
	FUTURE_VERSION,
	MISSING_VERSION,
	NOT_A_STRUCTURE,
	FAILED
}
