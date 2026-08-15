package de.cristelknight.structureupgrader.upgrade;

import java.nio.file.Path;

public record UpgradeRunResult(UpgradeReport report, Path reportPath) {
}
