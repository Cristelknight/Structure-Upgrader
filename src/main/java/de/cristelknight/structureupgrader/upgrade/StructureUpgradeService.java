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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
		int repaired = count(results, FileStatus.REPAIRED);
		int wouldRepair = count(results, FileStatus.WOULD_REPAIR);
		int failed = count(results, FileStatus.FAILED);
		int skipped = results.size() - upgraded - wouldUpgrade - repaired - wouldRepair - failed;
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
			repaired,
			wouldRepair,
			skipped,
			failed,
			aggregateRepairs(results),
			List.copyOf(results)
		);

		Path reportPath = mode.writesFiles()
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
		Integer sourceDataVersion = null;

		try {
			BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
			CompoundTag input = read(file);
			if (!StructureNbtValidator.isStructure(input)) {
				return result(relativePath, FileStatus.NOT_A_STRUCTURE, null, "NBT root is not a structure template");
			}

			sourceDataVersion = input.getInt("DataVersion").orElse(null);
			if (mode.isRepair()) {
				return processRepair(relativePath, file, input, sourceDataVersion, mode, before, runBackupDirectory);
			}

			if (sourceDataVersion == null && assumedDataVersion != null) {
				sourceDataVersion = assumedDataVersion;
			} else if (sourceDataVersion == null) {
				return result(relativePath, FileStatus.MISSING_VERSION, null, "Missing DataVersion");
			}

			if (sourceDataVersion > targetDataVersion) {
				return result(relativePath, FileStatus.FUTURE_VERSION, sourceDataVersion, "Newer than this mod's target; not downgraded");
			}
			if (sourceDataVersion == targetDataVersion) {
				return result(relativePath, FileStatus.CURRENT, sourceDataVersion, "Already current");
			}
			if (mode.isDryRun()) {
				return result(relativePath, FileStatus.WOULD_UPGRADE, sourceDataVersion, "Would be upgraded");
			}

			CompoundTag upgraded = DataFixTypes.STRUCTURE.updateToCurrentVersion(dataFixer, input, sourceDataVersion);
			upgraded.putInt("DataVersion", targetDataVersion);
			replace(file, upgraded, before, runBackupDirectory, verification -> {
				if (!StructureNbtValidator.isStructure(verification)
					|| verification.getIntOr("DataVersion", -1) != targetDataVersion) {
					throw new IOException("Temporary output failed verification");
				}
			});
			return result(relativePath, FileStatus.UPGRADED, sourceDataVersion, "Upgraded and backed up");
		} catch (Exception exception) {
			return result(relativePath, FileStatus.FAILED, sourceDataVersion, conciseMessage(exception));
		}
	}

	private FileResult processRepair(
		String relativePath,
		Path file,
		CompoundTag input,
		Integer sourceDataVersion,
		UpgradeMode mode,
		BasicFileAttributes before,
		Path runBackupDirectory
	) throws IOException {
		CompoundTag repaired = input.copy();
		List<RepairAction> actions = StructureRepairer.repair(repaired);
		if (actions.isEmpty()) {
			return result(relativePath, FileStatus.NO_REPAIRS, sourceDataVersion, "No known repairs needed");
		}

		int actionCount = actions.stream().mapToInt(RepairAction::count).sum();
		if (mode.isDryRun()) {
			return result(relativePath, FileStatus.WOULD_REPAIR, sourceDataVersion,
				"Would apply " + actionCount + " repair action(s)", actions);
		}

		TagSnapshot dataVersion = TagSnapshot.capture(input, "DataVersion");
		replace(file, repaired, before, runBackupDirectory, verification -> {
			if (!StructureNbtValidator.isStructure(verification)
				|| !dataVersion.matches(verification, "DataVersion")
				|| !StructureRepairer.repair(verification.copy()).isEmpty()) {
				throw new IOException("Temporary repaired output failed verification");
			}
		});
		return result(relativePath, FileStatus.REPAIRED, sourceDataVersion,
			"Applied " + actionCount + " repair action(s) and backed up the original", actions);
	}

	private void replace(
		Path file,
		CompoundTag output,
		BasicFileAttributes before,
		Path runBackupDirectory,
		OutputVerifier verifier
	) throws IOException {
		Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".structure-upgrader.tmp");
		try {
			NbtIo.writeCompressed(output, temporary);
			verifier.verify(read(temporary));

			BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
			if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
				throw new IOException("Source changed while it was being processed");
			}

			Path backup = runBackupDirectory.resolve(serverDirectory.relativize(file));
			Files.createDirectories(backup.getParent());
			Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("Filesystem does not support atomic replacement", exception);
			}
		} finally {
			Files.deleteIfExists(temporary);
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

	private FileResult result(String path, FileStatus status, Integer sourceDataVersion, String message, List<RepairAction> repairs) {
		return new FileResult(path, status, sourceDataVersion, targetDataVersion, message, repairs);
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

	private static List<RepairAction> aggregateRepairs(List<FileResult> results) {
		Map<String, Integer> counts = new TreeMap<>();
		for (FileResult result : results) {
			for (RepairAction repair : result.repairs()) {
				counts.merge(repair.ruleId(), repair.count(), Integer::sum);
			}
		}
		return counts.entrySet().stream()
			.map(entry -> new RepairAction(entry.getKey(), entry.getValue()))
			.toList();
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

	@FunctionalInterface
	private interface OutputVerifier {
		void verify(CompoundTag verification) throws IOException;
	}

	private record TagSnapshot(boolean present, net.minecraft.nbt.Tag value) {
		private static TagSnapshot capture(CompoundTag tag, String key) {
			net.minecraft.nbt.Tag value = tag.get(key);
			return new TagSnapshot(value != null, value == null ? null : value.copy());
		}

		private boolean matches(CompoundTag tag, String key) {
			net.minecraft.nbt.Tag candidate = tag.get(key);
			return present == (candidate != null) && Objects.equals(value, candidate);
		}
	}
}
