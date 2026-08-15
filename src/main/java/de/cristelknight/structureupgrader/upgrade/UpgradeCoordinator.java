package de.cristelknight.structureupgrader.upgrade;

import de.cristelknight.structureupgrader.StructureUpgrader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

public final class UpgradeCoordinator {
	private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter
		.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);
	private final AtomicReference<RunningJob> activeJob = new AtomicReference<>();
	private ExecutorService executor;

	public boolean start(UpgradeMode mode, String requestedPath, Integer assumedDataVersion, CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		StructureUpgradeService service;
		Path target;
		try {
			service = new StructureUpgradeService(
				server.getFixerUpper(),
				SharedConstants.getCurrentVersion().dataVersion().version(),
				server.getServerDirectory()
			);
			target = SafePathResolver.resolve(server.getServerDirectory(), service.backupDirectory(), requestedPath);
		} catch (IOException | RuntimeException exception) {
			source.sendFailure(Component.literal("Cannot process path: " + exception.getMessage()));
			return false;
		}

		String runId = RUN_ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
		RunningJob job = new RunningJob(runId, mode, requestedPath, target, assumedDataVersion, server);
		if (!activeJob.compareAndSet(null, job)) {
			source.sendFailure(Component.literal("Another Structure Upgrader job is already running"));
			return false;
		}

		try {
			executor().submit(() -> execute(job, service, source));
		} catch (RejectedExecutionException exception) {
			activeJob.compareAndSet(job, null);
			source.sendFailure(Component.literal("The Structure Upgrader worker is shutting down"));
			return false;
		}

		source.sendSuccess(
			() -> Component.literal("Started " + displayName(mode) + " job " + runId + " for " + target),
			false
		);
		return true;
	}

	public void status(CommandSourceStack source) {
		RunningJob job = activeJob.get();
		if (job == null) {
			source.sendSuccess(() -> Component.literal("No Structure Upgrader job is running"), false);
			return;
		}
		source.sendSuccess(() -> Component.literal(
			"Job " + job.runId + " (" + displayName(job.mode) + ") processed "
				+ job.processed + "/" + (job.total < 0 ? "?" : job.total)
				+ (job.cancelled ? "; cancellation requested" : "")
		), false);
	}

	public void cancel(CommandSourceStack source) {
		RunningJob job = activeJob.get();
		if (job == null) {
			source.sendFailure(Component.literal("No Structure Upgrader job is running"));
			return;
		}
		job.cancelled = true;
		source.sendSuccess(() -> Component.literal("Cancellation requested for job " + job.runId), false);
	}

	public synchronized void shutdown(MinecraftServer server) {
		RunningJob job = activeJob.get();
		if (job != null && job.server == server) {
			job.cancelled = true;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private void execute(RunningJob job, StructureUpgradeService service, CommandSourceStack source) {
		try {
			UpgradeRunResult result = service.run(
				job.runId,
				job.mode,
				job.requestedPath,
				job.target,
				job.assumedDataVersion,
				new StructureUpgradeService.JobControl() {
					@Override
					public boolean isCancelled() {
						return job.cancelled;
					}

					@Override
					public void onProgress(int processed, int total) {
						job.processed = processed;
						job.total = total;
						if (processed > 0 && (processed % 100 == 0 || processed == total)) {
							StructureUpgrader.LOGGER.info("Structure Upgrader job {} processed {}/{} files", job.runId, processed, total);
						}
					}
				}
			);

			UpgradeReport report = result.report();
			job.server.execute(() -> source.sendSuccess(() -> Component.literal(
				"Job " + job.runId + (report.cancelled() ? " cancelled" : " finished")
					+ ": upgraded=" + report.upgraded()
					+ ", wouldUpgrade=" + report.wouldUpgrade()
					+ ", repaired=" + report.repaired()
					+ ", wouldRepair=" + report.wouldRepair()
					+ ", skipped=" + report.skipped()
					+ ", failed=" + report.failed()
					+ ". Report: " + result.reportPath()
			), false));
		} catch (Exception exception) {
			StructureUpgrader.LOGGER.error("Structure Upgrader job {} failed", job.runId, exception);
			job.server.execute(() -> source.sendFailure(Component.literal(
				"Job " + job.runId + " failed: " + conciseMessage(exception)
			)));
		} finally {
			activeJob.compareAndSet(job, null);
		}
	}

	private synchronized ExecutorService executor() {
		if (executor == null || executor.isShutdown()) {
			ThreadFactory factory = runnable -> {
				Thread thread = new Thread(runnable, "structure-upgrader-worker");
				thread.setDaemon(true);
				return thread;
			};
			executor = Executors.newSingleThreadExecutor(factory);
		}
		return executor;
	}

	private static String conciseMessage(Exception exception) {
		return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
	}

	private static String displayName(UpgradeMode mode) {
		return switch (mode) {
			case SCAN -> "upgrade scan";
			case UPGRADE -> "upgrade";
			case REPAIR_SCAN -> "repair scan";
			case REPAIR -> "repair";
		};
	}

	private static final class RunningJob {
		private final String runId;
		private final UpgradeMode mode;
		private final String requestedPath;
		private final Path target;
		private final Integer assumedDataVersion;
		private final MinecraftServer server;
		private volatile boolean cancelled;
		private volatile int processed;
		private volatile int total = -1;

		private RunningJob(String runId, UpgradeMode mode, String requestedPath, Path target, Integer assumedDataVersion, MinecraftServer server) {
			this.runId = runId;
			this.mode = mode;
			this.requestedPath = requestedPath;
			this.target = target;
			this.assumedDataVersion = assumedDataVersion;
			this.server = server;
		}
	}
}
