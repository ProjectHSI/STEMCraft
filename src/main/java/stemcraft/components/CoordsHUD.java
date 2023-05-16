package stemcraft.components;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import stemcraft.objects.SMComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CoordsHUD extends SMComponent {
    private Map<UUID, Boolean> coordsEnabled;
    private Map<UUID, Boolean> timeEnabled;
    private BukkitTask task = null;

    public Boolean onEnable() {
        coordsEnabled = new HashMap<>();
        timeEnabled = new HashMap<>();

        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : smPlugin.getServer().getOnlinePlayers()) {
                    updateHUD(player);
                }
            }
        }.runTaskTimer(smPlugin, 20, 20); // Update every second (20 ticks)

        return true;
    }

    public void onDisable() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    public boolean areCoordsEnabled(Player player) {
        UUID playerId = player.getUniqueId();
        return coordsEnabled.getOrDefault(playerId, false);
    }

    public boolean areTimeEnabled(Player player) {
        UUID playerId = player.getUniqueId();
        return timeEnabled.getOrDefault(playerId, false);
    }

    public void toggleCoords(Player player) {
        UUID playerId = player.getUniqueId();
        boolean isEnabled = coordsEnabled.getOrDefault(playerId, false);
        coordsEnabled.put(playerId, !isEnabled);
    }

    public void toggleTime(Player player) {
        UUID playerId = player.getUniqueId();
        boolean isEnabled = timeEnabled.getOrDefault(playerId, false);
        timeEnabled.put(playerId, !isEnabled);
    }

    public void updateHUD(Player player) {
        UUID playerId = player.getUniqueId();
        boolean coordsEnabled = this.coordsEnabled.getOrDefault(playerId, false);
        boolean timeEnabled = this.timeEnabled.getOrDefault(playerId, false);

        if (coordsEnabled) {
            String coordinates = getFormattedCoordinates(player.getLocation());
            sendActionBar(player, coordinates);
        }

        if (timeEnabled) {
            String time = getFormattedTime(player.getWorld().getTime());
            sendActionBar(player, time);
        }
    }

    private String getFormattedCoordinates(Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return ChatColor.GRAY + "X: " + ChatColor.WHITE + x + " " + ChatColor.GRAY + "Y: " + ChatColor.WHITE + y + " "
                + ChatColor.GRAY + "Z: " + ChatColor.WHITE + z;
    }

    private String getFormattedTime(long ticks) {
        long hours = (ticks / 1000 + 6) % 24; // Adjust for in-game time offset
        long minutes = (ticks % 1000) * 60 / 1000;
        return ChatColor.GRAY + "Time: " + ChatColor.WHITE + hours + ":" + (minutes < 10 ? "0" : "") + minutes;
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));

    }
}