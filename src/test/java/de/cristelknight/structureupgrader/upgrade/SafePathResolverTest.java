package de.cristelknight.structureupgrader.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafePathResolverTest {
	@TempDir
	Path serverDirectory;

	@Test
	void resolvesAnExistingRelativeTarget() throws IOException {
		Path target = Files.createDirectories(serverDirectory.resolve("structures"));
		Path backup = serverDirectory.resolve("config/structure-upgrader/backups");
		assertEquals(target.toRealPath(), SafePathResolver.resolve(serverDirectory, backup, "structures"));
	}

	@Test
	void rejectsAbsoluteTraversalRootAndBackups() throws IOException {
		Path backup = Files.createDirectories(serverDirectory.resolve("config/structure-upgrader/backups"));
		assertThrows(IOException.class, () -> SafePathResolver.resolve(serverDirectory, backup, serverDirectory.toString()));
		assertThrows(IOException.class, () -> SafePathResolver.resolve(serverDirectory, backup, "."));
		assertThrows(IOException.class, () -> SafePathResolver.resolve(serverDirectory, backup, "../outside"));
		assertThrows(IOException.class, () -> SafePathResolver.resolve(serverDirectory, backup, "config/structure-upgrader/backups"));
	}
}
