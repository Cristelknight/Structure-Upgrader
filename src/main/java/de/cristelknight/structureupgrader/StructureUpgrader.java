package de.cristelknight.structureupgrader;

import de.cristelknight.structureupgrader.command.StructureUpgradeCommands;
import de.cristelknight.structureupgrader.upgrade.UpgradeCoordinator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StructureUpgrader implements ModInitializer {
	public static final String MOD_ID = "structure-upgrader";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final UpgradeCoordinator COORDINATOR = new UpgradeCoordinator();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			StructureUpgradeCommands.register(dispatcher, COORDINATOR)
		);
		ServerLifecycleEvents.SERVER_STOPPING.register(COORDINATOR::shutdown);
		LOGGER.info("Structure Upgrader initialized for Minecraft 1.21.1");
	}
}
