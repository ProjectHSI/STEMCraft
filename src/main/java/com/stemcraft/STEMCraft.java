package com.stemcraft;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.stemcraft.core.SMBridge;
import com.stemcraft.core.SMCommon;
import com.stemcraft.core.SMDatabase;
import com.stemcraft.core.SMDebugger;
import com.stemcraft.core.SMFeature;
import com.stemcraft.core.SMLocale;
import com.stemcraft.core.SMMessenger;
import com.stemcraft.core.SMTask;
import com.stemcraft.core.command.SMCommand;
import com.stemcraft.core.config.SMConfig;
import com.stemcraft.core.interfaces.SMCallback;
import com.stemcraft.core.tabcomplete.SMTabComplete;
import lombok.NonNull;
import net.md_5.bungee.api.ChatColor;

public class STEMCraft extends JavaPlugin implements Listener {
    /*
     * Plugin instance.
     */
    private static STEMCraft plugin;

    /**
     * A list of required plugins.
     */
    String[] requiredPlugins = {"NBTAPI", "PlaceholderAPI", "WorldEdit", "WorldGuard"};

    /**
     * Can the plugin meet the requirements to be enabled.
     */
    private Boolean allowEnable = true;

    /**
     * A list of loaded features in the plugin.
     */
    private static HashMap<String, SMFeature> features = new HashMap<>();

    private static HashMap<String, Long> runOnceMap = new HashMap<>();
    private static HashMap<String, SMTask> runOnceMapDelay = new HashMap<>();

    /**
     * The display version. Can be set in config. Defaults to plugin version.
     */
    private static String displayVersion = null;

    /**
     * On Bukkit Plugin load
     */
    @Override
    public void onLoad() {
        // Set plugin instance
        STEMCraft.plugin = this;

        // Set messenger prefixes
        SMMessenger.setInfoPrefix(SMConfig.main().getString("message.prefix.info"));
        SMMessenger.setSuccessPrefix(SMConfig.main().getString("message.prefix.success"));
        SMMessenger.setWarnPrefix(SMConfig.main().getString("message.prefix.warn"));
        SMMessenger.setErrorPrefix(SMConfig.main().getString("message.prefix.error"));
        SMMessenger.setAnnouncePrefix(SMConfig.main().getString("message.prefix.announce"));

        // Load locales
        SMLocale.loadAll();

        // Check required plugins are installed
        for (String pluginName : this.requiredPlugins) {
            if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
                getLogger().severe(pluginName + " is not installed! STEMCraft requires " + pluginName);
                this.allowEnable = false;
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }

        // Load plugin features
        loadFeatures();

        getLogger().info("STEMCraft " + getVersion() + " loaded");
    }

    /**
     * On Bukkit Plugin Enable
     */
    @Override
    public void onEnable() {
        // Can we be enabled?
        if (this.allowEnable == false) {
            getLogger().severe("STEMCraft was not enabled because a dependency was missing");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Check required plugins are enabled
        for (String pluginName : this.requiredPlugins) {
            if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
                getLogger().severe(pluginName + " is not enabled! This plugin requires " + pluginName);
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }

        // Connect to Database
        if (!SMDatabase.isConnected()) {
            SMDatabase.connect();
        }

        // Setup Default Tab Completions
        SMTabComplete.register("player", () -> {
            List<String> names = new ArrayList<>();

            Bukkit.getServer().getOnlinePlayers().forEach(player -> {
                names.add(player.getName());
            });

            return names;
        });

        SMTabComplete.register("material", () -> {
            return SMBridge.getMaterialList();
        });

        SMTabComplete.register("world", () -> {
            List<String> names = new ArrayList<>();

            Bukkit.getServer().getWorlds().forEach(world -> {
                names.add(world.getName());
            });

            return names;
        });

        // Enable features
        features.forEach((name, instance) -> {
            enableFeature(name);
        });

        new SMCommand("stemcraft")
            .tabComplete("info")
            .tabComplete("reload")
            .action(ctx -> {
                if (ctx.args.size() == 0) {
                    ctx.returnInvalidArgs();
                } else {
                    if ("info".equalsIgnoreCase(ctx.args.get(0))) {
                        ctx.returnInfo("STEMCraft " + STEMCraft.getVersion());
                    } else if ("reload".equalsIgnoreCase(ctx.args.get(0))) {
                        ctx.checkPermission("stemcraft.reload");

                        onDisable();
                        SMConfig.reloadAll();
                        onLoad();
                        onEnable();

                        ctx.returnInfo("STEMCraft reloaded");
                    } else {
                        ctx.returnInvalidArgs();
                    }
                }
            }).register();
    }

    /**
     * On Bukkit Plugin Disable
     */
    @Override
    public void onDisable() {
        // Disable features
        features.forEach((name, instance) -> {
            if (instance.isEnabled()) {
                instance.disable();
                if (!instance.isEnabled()) {
                    getLogger().info("Feature " + name + " disabled");
                } else {
                    getLogger().info("Feature " + name + " could not be disabled");
                }
            }
        });

        // Disconnect from Database
        if (SMDatabase.isConnected()) {
            SMDatabase.disconnect();
        }
    }

    /**
     * When receiving the Plugin Disable Event
     * 
     * @param event
     */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this) {
            onDisable();
        }
    }

    /**
     * Get the plugin instance.
     * 
     * @return
     */
    public static STEMCraft getPlugin() {
        return plugin;
    }

    /**
     * JarFileProcessor callback interface
     */
    @FunctionalInterface
    public interface JarFileProcessor {
        void process(JarEntry jarFile);
    }

    /**
     * Iterate plugin files using the callback
     * 
     * @param callback
     */
    public static void iteratePluginFiles(String path, JarFileProcessor callback) {
        try {
            File pluginFile = new File(STEMCraft.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (pluginFile.getPath().endsWith(".jar")) {
                try (JarInputStream jarInputStream = new JarInputStream(new FileInputStream(pluginFile))) {
                    JarEntry jarEntry;
                    while ((jarEntry = jarInputStream.getNextJarEntry()) != null) {
                        if (path != null && path != "") {
                            String className = jarEntry.getName();
                            if (!className.startsWith(path)) {
                                continue;
                            }
                        }

                        callback.process(jarEntry);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Get the plugin version.
     * 
     * @return
     */
    public static String getVersion() {
        return getPlugin().getDescription().getVersion();
    }

    /**
     * Get the plugin display version. Can be set in config.
     * 
     * @return The display version of the plugin.
     */
    public static String getDisplayVersion() {
        if (displayVersion == null) {
            displayVersion = SMConfig.main().getString("display-version");
            if (displayVersion == null) {
                displayVersion = getVersion();
            }
        }

        return displayVersion;
    }

    /**
     * Return if a specific feature is enabled.
     * 
     * @param name
     * @return
     */
    public static Boolean featureEnabled(String name) {
        if (features.containsKey(name)) {
            return features.get(name).isEnabled();
        }

        return false;
    }

    /**
     * Return the instance of a specific feature.
     * 
     * @param name
     * @return
     */
    public static <T extends SMFeature> T getFeature(String name, Class<T> featureClass) {
        if (features.containsKey(name)) {
            SMFeature foundFeature = features.get(name);

            if (featureClass.isInstance(foundFeature)) {
                return featureClass.cast(foundFeature);
            }
        }

        return null;
    }

    /**
     * Load the plugin features.
     */
    private static void loadFeatures() {
        iteratePluginFiles("com/stemcraft/feature/", jar -> {
            String className = jar.getName();

            if (className.endsWith(".class")) {
                try {
                    Class<?> classItem = Class.forName(
                        className.substring(0, className.length() - 6).replaceAll("/", "."));

                    if (SMFeature.class.isAssignableFrom(classItem)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends SMFeature> smFeatureClass = (Class<? extends SMFeature>) classItem;
                        Constructor<?> constructor = smFeatureClass.getDeclaredConstructor();
                        SMFeature featureInstance = (SMFeature) constructor.newInstance();
                        String featureName = featureInstance.getName();

                        if (featureName.length() > 0 && !features.containsKey(featureName)) {
                            if (featureInstance.onLoad()) {
                                features.put(featureName, featureInstance); // Store the instance, not the class
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private static Boolean enableFeature(String name) {
        Logger logger = STEMCraft.getPlugin().getLogger();

        if (SMConfig.main().getBoolean("features." + name, true)) {
            SMFeature instance = features.getOrDefault(name, null);
            if (instance != null) {
                if (!instance.isEnabled()) {
                    // check required features are enabled
                    for (String required : instance.getRequireFeatures()) {
                        if (!enableFeature(required)) {
                            logger
                                .info("Feature " + name + " not enabled as it requires " + required + " to be enabled");
                            return false;
                        }
                    }

                    // perform any load befores
                    for (String loadAfter : instance.getLoadAfterFeatures()) {
                        enableFeature(loadAfter);
                    }

                    instance.enable();
                    if (instance.isEnabled()) {
                        logger.info("Feature " + name + " enabled");
                        return true;
                    }
                } else {
                    return true;
                }
            }

            logger.info("Feature " + name + " not enabled");
            return false;
        } else {
            logger.info("Feature " + name + " disabled in config");
            return false;
        }
    }

    /**
     * Send a info message to the console.
     * 
     * @param message
     */
    public static void info(String message) {
        if (Bukkit.getConsoleSender() != null) {
            Bukkit.getConsoleSender().sendMessage("[" + getNamed() + "] " + SMCommon.colorize(message));
        } else {
            Bukkit.getLogger().info(SMCommon.stripColors(message));
        }
    }

    /**
     * Send a warn message to the console.
     * 
     * @param message
     */
    public static void warning(String message) {
        if (Bukkit.getConsoleSender() != null) {
            Bukkit.getConsoleSender().sendMessage(SMCommon.colorize(message));
        } else {
            Bukkit.getLogger().warning(SMCommon.stripColors(message));
        }
    }

    /**
     * Send a severe message to the console.
     * 
     * @param message
     */
    public static void severe(String message) {
        if (Bukkit.getConsoleSender() != null) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.COLOR_CHAR + "c" + SMCommon.colorize(message));
        } else {
            Bukkit.getLogger().severe(SMCommon.stripColors(message));
        }
    }

    public static void error(@NonNull Throwable throwable, final String... messages) {
        if (throwable instanceof InvocationTargetException && throwable.getCause() != null)
            throwable = throwable.getCause();

        SMDebugger.printError(throwable, messages);
    }


    public static SMTask runLater(final Runnable runnable) {
        return runLater(1, runnable);
    }

    public static SMTask runLater(final int delayTicks, final Runnable runnable) {
        if (runIfDisabled(runnable)) {
            return null;
        }

        try {
            final SMTask task =
                SMTask.fromBukkit(Bukkit.getScheduler().runTaskLater(STEMCraft.getPlugin(), runnable, delayTicks));
            return task;
        } catch (final NoSuchMethodError err) {
            return SMTask.fromBukkit(
                Bukkit.getScheduler().scheduleSyncDelayedTask(STEMCraft.getPlugin(), runnable, delayTicks), false);
        }
    }

    public static SMTask runAsync(final Runnable runnable) {
        return runLaterAsync(0, runnable);
    }

    @SuppressWarnings("deprecation")
    public static SMTask runLaterAsync(final int delayTicks, final Runnable runnable) {
        if (runIfDisabled(runnable)) {
            return null;
        }

        try {
            final SMTask task = SMTask.fromBukkit(
                Bukkit.getScheduler().runTaskLaterAsynchronously(STEMCraft.getPlugin(), runnable, delayTicks));

            return task;

        } catch (final NoSuchMethodError err) {
            return SMTask.fromBukkit(
                Bukkit.getScheduler().scheduleAsyncDelayedTask(STEMCraft.getPlugin(), runnable, delayTicks), true);
        }
    }

    public static SMTask runTimer(final int repeatTicks, final Runnable runnable) {
        return runTimer(0, repeatTicks, runnable);
    }

    /**
     * Runs the given task after the given delay with a fixed delay between repetitions, even if the plugin is disabled
     * for some reason.
     *
     * @param delayTicks the delay (in ticks) to wait before running the task.
     * @param repeatTicks the delay (in ticks) between repetitions of the task.
     * @param runnable the task to be run.
     * @return the {@link SimpleTask} representing the scheduled task, or {@code null}.
     */
    public static SMTask runTimer(final int delayTicks, final int repeatTicks, final Runnable runnable) {
        if (runIfDisabled(runnable))
            return null;

        try {
            final SMTask task = SMTask.fromBukkit(
                Bukkit.getScheduler().runTaskTimer(STEMCraft.getPlugin(), runnable, delayTicks, repeatTicks));

            return task;

        } catch (final NoSuchMethodError err) {
            return SMTask.fromBukkit(Bukkit.getScheduler().scheduleSyncRepeatingTask(STEMCraft.getPlugin(), runnable,
                delayTicks, repeatTicks), false);
        }
    }

    /**
     * Runs the given task asynchronously on the next tick with a fixed delay between repetitions, even if the plugin is
     * disabled for some reason.
     *
     * @param repeatTicks the delay (in ticks) between repetitions of the task.
     * @param task the task to be run.
     * @return the {@link SimpleTask} representing the scheduled task, or {@code null}.
     */
    public static SMTask runTimerAsync(final int repeatTicks, final Runnable task) {
        return runTimerAsync(0, repeatTicks, task);
    }

    /**
     * Runs the given task after the given delay with a fixed delay between repetitions, even if the plugin is disabled
     * for some reason.
     *
     * @param delayTicks the delay (in ticks) to wait before running the task.
     * @param repeatTicks the delay (in ticks) between repetitions of the task.
     * @param runnable the task to be run.
     * @return the {@link SimpleTask} representing the scheduled task, or {@code null}.
     */
    @SuppressWarnings("deprecation")
    public static SMTask runTimerAsync(final int delayTicks, final int repeatTicks, final Runnable runnable) {
        if (runIfDisabled(runnable))
            return null;

        try {
            final SMTask task = SMTask.fromBukkit(Bukkit.getScheduler()
                .runTaskTimerAsynchronously(STEMCraft.getPlugin(), runnable, delayTicks, repeatTicks));

            return task;
        } catch (final NoSuchMethodError err) {
            return SMTask.fromBukkit(Bukkit.getScheduler().scheduleAsyncRepeatingTask(STEMCraft.getPlugin(), runnable,
                delayTicks, repeatTicks), true);
        }
    }

    /**
     * Runs the specified task if the plugin is disabled.
     * <p>
     * In case the plugin is disabled, this method will return {@code true} and the task will be run. Otherwise, we
     * return {@code false} and the task is run correctly in Bukkit's scheduler.
     * <p>
     * This is a fail-safe for critical save-on-exit operations in case the plugin malfunctions or is improperly
     * reloaded using a plugin manager such as PlugMan.
     *
     * @param runnable the task to be run.
     * @return {@code true} if the task was run, or {@code false} if the plugin is enabled.
     */
    private static boolean runIfDisabled(final Runnable runnable) {
        if (!STEMCraft.getPlugin().isEnabled()) {
            runnable.run();

            return true;
        }

        return false;
    }

    /**
     * Run a callback, cancelling any other requests with the same id within the blockingTime.
     * 
     * @param id
     * @param blockingTime
     * @param callback
     */
    public static void runOnce(final String id, final long blockingTime, final SMCallback callback) {
        long currentMs = System.currentTimeMillis();

        if (!runOnceMap.containsKey(id) || runOnceMap.get(id) < currentMs) {
            runOnceMap.put(id, currentMs + blockingTime);
            callback.run();
        }
    }

    /**
     * Run a callback once after a delay. Calls with the same id will cancel within the delay will cancel the original
     * callback.
     * 
     * @param id
     * @param delayTime
     * @param callback
     */
    public static void runOnceDelay(final String id, final long delayTime, final SMCallback callback) {
        if (runOnceMapDelay.containsKey(id)) {
            runOnceMapDelay.get(id).cancel();
        }

        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                runOnceMapDelay.remove(id);
                callback.run();
            }
        };

        SMTask task = SMTask.fromBukkit(runnable.runTaskLater(STEMCraft.getPlugin(), delayTime));
        runOnceMapDelay.put(id, task);
    }

    /**
     * Get the plugin name
     * 
     * @return
     */
    public static String getNamed() {
        return STEMCraft.getPlugin().getDescription().getName();
    }
}
