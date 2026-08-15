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
	int skipped,
	int failed,
	List<FileResult> files
) {
}
