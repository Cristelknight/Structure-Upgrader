package de.cristelknight.structureupgrader.upgrade;

public record FileResult(
	String path,
	FileStatus status,
	Integer sourceDataVersion,
	int targetDataVersion,
	String message
) {
}
