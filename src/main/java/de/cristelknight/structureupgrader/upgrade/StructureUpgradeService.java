package de.cristelknight.structureupgrader.upgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.DataFixTypes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class StructureUpgradeService {
	public static final long MAX_EXPANDED_NBT_BYTES = 512L * 1024L * 1024L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final DataFixer dataFixer;
	private final int targetDataVersion;
	private final Path serverDirectory;
	private final Path backupDirectory;
	private final Path reportDirectory;

	public StructureUpgradeService(DataFixer dataFixer, int targetDataVersion, Path serverDirectory) throws IOException {
		this.dataFixer = dataFixer;
		this.targetDataVersion = targetDataVersion;
		this.serverDirectory = serverDirectory.toRealPath();
		Path modDirectory = this.serverDirectory.resolve("config").resolve("structure-upgrader");
		this.backupDirectory = modDirectory.resolve("backups");
		this.reportDirectory = modDirectory.resolve("reports");
	}

	public Path backupDirectory() {
		return backupDirectory;
	}

	public UpgradeRunResult run(
		String runId,
		UpgradeMode mode,
		String requestedPath,
		Path target,
		Integer assumedDataVersion,
		JobControl control
	) throws IOException {
		String startedAt = Instant.now().toString();
		List<Path> files = discover(target);
		List<FileResult> results = new ArrayList<>(files.size());
		Path runBackupDirectory = backupDirectory.resolve(runId);
		control.onProgress(0, files.size());

		for (int index = 0; index < files.size(); index++) {
			if (control.isCancelled() || Thread.currentThread().isInterrupted()) {
				break;
			}

			Path file = files.get(index);
			results.add(process(file, mode, assumedDataVersion, runBackupDirectory));
			control.onProgress(index + 1, files.size());
		}

		boolean cancelled = results.size() < files.size();
		int upgraded = count(results, FileStatus.UPGRADED);
		int wouldUpgrade = count(results, FileStatus.WOULD_UPGRADE);
		int failed = count(results, FileStatus.FAILED);
		int skipped = results.size() - upgraded - wouldUpgrade - failed;
		UpgradeReport report = new UpgradeReport(
			runId,
			mode,
			requestedPath,
			targetDataVersion,
			startedAt,
			Instant.now().toString(),
			cancelled,
			files.size(),
			upgraded,
			wouldUpgrade,
			skipped,
			failed,
			List.copyOf(results)
		);

		Path reportPath = mode == UpgradeMode.UPGRADE
			? runBackupDirectory.resolve("report.json")
			: reportDirectory.resolve(runId + ".json");
		writeReport(reportPath, report);
		return new UpgradeRunResult(report, reportPath);
	}

	private List<Path> discover(Path target) throws IOException {
		List<Path> files = new ArrayList<>();
		if (Files.isRegularFile(target)) {
			if (isNbtFile(target)) {
				files.add(target);
			}
			return files;
		}

		Files.walkFileTree(target, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
				if (!directory.equals(target)
					&& (Files.isSymbolicLink(directory) || directory.startsWith(backupDirectory))) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (attributes.isRegularFile() && isNbtFile(file) && !file.startsWith(backupDirectory)) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});

		files.sort(Comparator.comparing(Path::toString));
		return files;
	}

	private FileResult process(Path file, UpgradeMode mode, Integer assumedDataVersion, Path runBackupDirectory) {
		String relativePath = relativePath(file);
		Path temporary = null;
		Integer sourceDataVersion = null;

		try {
			BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
			CompoundTag input = read(file);
			if (!StructureNbtValidator.isStructure(input)) {
				return result(relativePath, FileStatus.NOT_A_STRUCTURE, null, "NBT root is not a structure template");
			}

			if (input.getInt("DataVersion").isPresent()) {
				sourceDataVersion = input.getIntOr("DataVersion", 0);
			} else if (assumedDataVersion != null) {
				sourceDataVersion = assumedDataVersion;
			} else {
				return result(relativePath, FileStatus.MISSING_VERSION, null, "Missing DataVersion");
			}

			if (sourceDataVersion > targetDataVersion) {
				return result(relativePath, FileStatus.FUTURE_VERSION, sourceDataVersion, "Newer than this mod's target; not downgraded");
			}
			if (sourceDataVersion == targetDataVersion) {
				return result(relativePath, FileStatus.CURRENT, sourceDataVersion, "Already current");
			}
			if (mode == UpgradeMode.SCAN) {
				return result(relativePath, FileStatus.WOULD_UPGRADE, sourceDataVersion, "Would be upgraded");
			}

			CompoundTag upgraded = DataFixTypes.STRUCTURE.updateToCurrentVersion(dataFixer, input, sourceDataVersion);
			upgraded.putInt("DataVersion", targetDataVersion);

			temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".structure-upgrader.tmp");
			NbtIo.writeCompressed(upgraded, temporary);
			CompoundTag verification = read(temporary);
			if (!StructureNbtValidator.isStructure(verification)
				|| verification.getIntOr("DataVersion", -1) != targetDataVersion) {
				throw new IOException("Temporary output failed verification");
			}

			BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
			if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
				throw new IOException("Source changed while it was being upgraded");
			}

			Path backup = runBackupDirectory.resolve(serverDirectory.relativize(file));
			Files.createDirectories(backup.getParent());
			Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("Filesystem does not support atomic replacement", exception);
			}
			temporary = null;
			return result(relativePath, FileStatus.UPGRADED, sourceDataVersion, "Upgraded and backed up");
		} catch (Exception exception) {
			return result(relativePath, FileStatus.FAILED, sourceDataVersion, conciseMessage(exception));
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The original was never replaced. A stale temporary file is harmless and visible.
				}
			}
		}
	}

	private CompoundTag read(Path path) throws IOException {
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_EXPANDED_NBT_BYTES));
	}

	private void writeReport(Path path, UpgradeReport report) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = Files.createTempFile(path.getParent(), ".report-", ".tmp");
		try {
			Files.writeString(temporary, GSON.toJson(report));
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private FileResult result(String path, FileStatus status, Integer sourceDataVersion, String message) {
		return new FileResult(path, status, sourceDataVersion, targetDataVersion, message);
	}

	private String relativePath(Path file) {
		try {
			return serverDirectory.relativize(file).toString().replace('\\', '/');
		} catch (IllegalArgumentException exception) {
			return file.toString();
		}
	}

	private static int count(List<FileResult> results, FileStatus status) {
		return (int) results.stream().filter(result -> result.status() == status).count();
	}

	private static boolean isNbtFile(Path path) {
		return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt");
	}

	private static String conciseMessage(Exception exception) {
		String message = exception.getMessage();
		return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	public interface JobControl {
		boolean isCancelled();

		void onProgress(int processed, int total);
	}
}
