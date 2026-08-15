package de.cristelknight.structureupgrader.upgrade;

import java.io.IOException;
import java.nio.file.Path;

public final class SafePathResolver {
	private SafePathResolver() {
	}

	public static Path resolve(Path serverDirectory, Path backupDirectory, String requestedPath) throws IOException {
		Path input = Path.of(requestedPath);
		if (input.isAbsolute()) {
			throw new IOException("Absolute paths are not allowed");
		}

		Path root = serverDirectory.toRealPath();
		Path candidate = root.resolve(input).normalize();
		if (!candidate.startsWith(root) || candidate.equals(root)) {
			throw new IOException("The target must be a non-root path inside the game/server directory");
		}

		Path target = candidate.toRealPath();
		if (!target.startsWith(root) || target.equals(root)) {
			throw new IOException("The resolved target escapes the game/server directory");
		}

		Path backup = backupDirectory.toAbsolutePath().normalize();
		if (target.startsWith(backup)) {
			throw new IOException("Backup directories cannot be processed");
		}

		return target;
	}
}
