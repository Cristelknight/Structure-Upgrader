package de.cristelknight.structureupgrader.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.cristelknight.structureupgrader.upgrade.UpgradeCoordinator;
import de.cristelknight.structureupgrader.upgrade.UpgradeMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class StructureUpgradeCommands {
	private StructureUpgradeCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, UpgradeCoordinator coordinator) {
		dispatcher.register(Commands.literal("structureupgrader")
			.requires(source -> source.hasPermission(4))
			.then(Commands.literal("scan")
				.then(Commands.argument("path", StringArgumentType.greedyString())
					.executes(context -> coordinator.start(UpgradeMode.SCAN, StringArgumentType.getString(context, "path"), null, context.getSource()) ? 1 : 0)))
			.then(Commands.literal("upgrade")
				.then(Commands.literal("assume")
					.then(Commands.argument("dataVersion", IntegerArgumentType.integer(0))
						.then(Commands.argument("path", StringArgumentType.greedyString())
							.executes(context -> coordinator.start(
								UpgradeMode.UPGRADE,
								StringArgumentType.getString(context, "path"),
								IntegerArgumentType.getInteger(context, "dataVersion"),
								context.getSource()
							) ? 1 : 0))))
				.then(Commands.argument("path", StringArgumentType.greedyString())
					.executes(context -> coordinator.start(UpgradeMode.UPGRADE, StringArgumentType.getString(context, "path"), null, context.getSource()) ? 1 : 0)))
			.then(Commands.literal("status")
				.executes(context -> {
					coordinator.status(context.getSource());
					return 1;
				}))
			.then(Commands.literal("cancel")
				.executes(context -> {
					coordinator.cancel(context.getSource());
					return 1;
				}))
		);
	}
}
