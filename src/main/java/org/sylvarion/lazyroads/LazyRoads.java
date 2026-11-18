package org.sylvarion.lazyroads;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class LazyRoads extends JavaPlugin {
    private static final long MAX_SCAN_BLOCKS = 2_000_000;
    private RoadOperations operations;

    @Override
    public void onEnable() {
        migrateConfiguration();
        saveDefaultConfig();
        try {
            operations = readOperations();
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not read the LazyRoads configuration", exception);
        }
        registerRoadCommand("/roadsmooth", "lazyroads.smooth", "Smooth one-block rises in a FAWE cuboid",
                "No slab transitions were placed in the selection.", () -> operations.smooth());
        registerRoadCommand("/roaddash", "lazyroads.dash", "Add dashed center markings in a FAWE cuboid",
                "No dashes were placed. Select a full-width stretch of unmarked polished deepslate road.", () -> operations.dash());
        registerCommand("lazyroads", "Reload the LazyRoads configuration", new BasicCommand() {
            @Override
            public String permission() {
                return "lazyroads.reload";
            }

            @Override
            public void execute(CommandSourceStack source, String[] args) {
                var sender = source.getSender();
                if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                    sender.sendMessage("Usage: /lazyroads reload");
                    return;
                }

                try {
                    operations = readOperations();
                    sender.sendMessage("LazyRoads configuration reloaded.");
                } catch (IOException | InvalidConfigurationException exception) {
                    sender.sendMessage("LazyRoads kept its previous settings. Check config.yml and the server log.");
                    getLogger().log(Level.WARNING, "Could not reload the LazyRoads configuration", exception);
                }
            }
        });
    }

    private RoadOperations readOperations() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(getDataFolder().toPath().resolve("config.yml").toFile());
        if (config.contains("dashes", true) && !config.isConfigurationSection("dashes")) {
            throw new InvalidConfigurationException("dashes must contain dash-length and gap-length settings.");
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (Reader reader = getTextResource("config.yml")) {
            defaults.load(reader);
        }
        config.setDefaults(defaults);

        int dashLength = positiveLength(config, "dashes.dash-length");
        int gapLength = positiveLength(config, "dashes.gap-length");
        RoadSmoother smoother = new RoadSmoother(config, getLogger());
        RoadDasher dasher = new RoadDasher(dashLength, gapLength);
        return new RoadOperations(smoother::smooth, dasher::dash);
    }

    private int positiveLength(YamlConfiguration config, String key) throws InvalidConfigurationException {
        if (config.get(key) instanceof Integer length && length > 0) {
            return length;
        }
        throw new InvalidConfigurationException(key + " must be a positive whole number of blocks.");
    }

    private void migrateConfiguration() {
        Path folder = getDataFolder().toPath();
        Path destination = folder.resolve("config.yml");
        Path legacy = folder.resolveSibling("FAWERoadAssistant").resolve("config.yml");
        if (Files.exists(destination) || !Files.isRegularFile(legacy)) {
            return;
        }

        try {
            Files.createDirectories(folder);
            Path temporary = Files.createTempFile(folder, "config-", ".tmp");
            try {
                Files.copy(legacy, temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary, destination);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not copy the FAWERoadAssistant configuration to LazyRoads", exception);
        }
    }

    private void registerRoadCommand(String name, String permission, String description, String noChanges,
                                     Supplier<RoadOperation> operation) {
        registerCommand(name, description, new BasicCommand() {
            @Override
            public String permission() {
                return permission;
            }

            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (!(source.getSender() instanceof Player player)) {
                    source.getSender().sendMessage("Run this command in-game with a FAWE cuboid selected.");
                    return;
                }
                boolean preview = args.length == 1 && args[0].equalsIgnoreCase("preview");
                if (args.length != 0 && !preview) {
                    player.sendMessage("Usage: /" + name + " [preview]");
                    return;
                }
                start(player, name, operation.get(), preview, noChanges);
            }
        });
    }

    private record RoadOperations(RoadOperation smooth, RoadOperation dash) {}

    private void start(Player player, String name, RoadOperation operation, boolean preview, String noChanges) {
        WorldEdit worldEdit = WorldEdit.getInstance();
        var actor = BukkitAdapter.adapt(player);
        LocalSession session = worldEdit.getSessionManager().get(actor);
        World world = BukkitAdapter.adapt(player.getWorld());

        CuboidRegion cuboid;
        try {
            Region selected = session.getSelection(world);
            if (!(selected instanceof CuboidRegion region)) {
                player.sendMessage("Use //sel cuboid and select both corners of the road first.");
                return;
            }
            cuboid = region.clone();
        } catch (IncompleteRegionException exception) {
            player.sendMessage("Select both corners of the road with the FAWE wand first.");
            return;
        }

        if ((long) cuboid.getWidth() * cuboid.getHeight() * cuboid.getLength() > MAX_SCAN_BLOCKS) {
            player.sendMessage("Select a smaller section. The limit is " + MAX_SCAN_BLOCKS + " blocks per scan.");
            return;
        }

        actor.queueAction(() -> {
            if (!isEnabled() || !actor.getSessionKey().isActive()) {
                return;
            }
            try {
                EditSession edit = worldEdit.newEditSessionBuilder()
                        .world(world)
                        .actor(actor)
                        .command("/" + name)
                        .fastMode(false)
                        .combineStages(false)
                        .build();
                int count;
                try (edit) {
                    if (session.getMask() != null) {
                        edit.setMask(session.getMask());
                    }
                    count = operation.apply(edit, cuboid, preview);
                } finally {
                    if (!preview) {
                        session.remember(edit);
                    }
                }

                if (preview) {
                    actor.printInfo(TextComponent.of("Found " + count + " road surface positions to update. Run /" + name + " to apply."));
                } else if (count == 0) {
                    actor.printInfo(TextComponent.of(noChanges));
                } else {
                    actor.printInfo(TextComponent.of("Updated " + count + " road surface positions. Use //undo to revert."));
                }
            } catch (Exception exception) {
                actor.printError("/" + name + " stopped. Check the server log for details.");
                getLogger().log(Level.WARNING, "/" + name + " failed", exception);
            }
        });
    }

    @FunctionalInterface
    private interface RoadOperation {
        int apply(EditSession edit, CuboidRegion region, boolean preview) throws WorldEditException;
    }
}
