package de.cristelknight.structureupgrader.upgrade;

import java.util.List;

public record UpgradeReport(
	String runId,
	UpgradeMode mode,
	String requestedPath,
	int targetDataVersion,
	String startedAt,
	String finishedAt,
	boolean cancelled,
	int discovered,
	int upgraded,
	int wouldUpgrade,
	int repaired,
	int wouldRepair,
	int skipped,
	int failed,
	List<RepairAction> repairActions,
	List<FileResult> files
) {
}
