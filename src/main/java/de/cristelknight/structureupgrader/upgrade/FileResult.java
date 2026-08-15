package de.cristelknight.structureupgrader.upgrade;

import java.util.List;

public record FileResult(
	String path,
	FileStatus status,
	Integer sourceDataVersion,
	int targetDataVersion,
	String message,
	List<RepairAction> repairs
) {
	public FileResult(String path, FileStatus status, Integer sourceDataVersion, int targetDataVersion, String message) {
		this(path, status, sourceDataVersion, targetDataVersion, message, List.of());
	}
}
