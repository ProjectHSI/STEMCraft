package com.stemcraft.feature;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.stemcraft.STEMCraft;
import com.stemcraft.core.SMCommon;
import com.stemcraft.core.SMFeature;
import com.stemcraft.core.SMLocale;
import com.stemcraft.core.SMMessenger;
import com.stemcraft.core.SMReplacer;
import com.stemcraft.core.command.SMCommand;
import com.stemcraft.core.event.SMEvent;
import com.stemcraft.core.tabcomplete.SMTabComplete;
import com.stemcraft.core.util.SMWorldRegion;

/**
 * Workshop Feature
 * 
 * - Create a world or region with the prefix `workshop_`. The following text will be the workshop name - Regions by
 * default are protected from players - Region enter/exit text will be handled by the class - Players need to be in the
 * group the same name as the workshop, including the prefix - Add the same group with the suffix `_active` as a group
 * member to the region. DO NOT ADD THE workshop group to the region. The `_active` group will be given/removed from the
 * player automagically
 */
public class SMWorkshop extends SMFeature {
    RegionContainer container = null;

    public SMWorkshop() {
        requireFeatures.add("SMLuckPerms");
    }

    @Override
    protected Boolean onEnable() {
        container = WorldGuard.getInstance().getPlatform().getRegionContainer();

        SMTabComplete.register("workshops", () -> {
            List<String> list = new ArrayList<>();

            for (World world : Bukkit.getWorlds()) {
                if (world.getName().startsWith("workshop_")) {
                    list.add(world.getName().substring(9));
                }

                RegionManager regionManager = container.get(BukkitAdapter.adapt(world));

                if (regionManager != null) {
                    for (ProtectedRegion region : regionManager.getRegions().values()) {
                        if (region.getId().startsWith("workshop_")) {
                            list.add(region.getId().substring(9));
                        }
                    }
                }
            }

            return list;
        });

        SMEvent.register(PlayerTeleportEvent.class, ctx -> {
            STEMCraft.runLater(5, () -> {
                checkRegionMove(ctx.event.getPlayer(), ctx.event.getFrom(), ctx.event.getTo());
            });
        });

        SMEvent.register(PlayerMoveEvent.class, ctx -> {
            checkRegionMove(ctx.event.getPlayer(), ctx.event.getFrom(), ctx.event.getTo());
        });

        new SMCommand("workshop")
            .tabComplete("{workshops}")
            .action(ctx -> {
                ctx.checkNotConsole();
                ctx.checkArgs(1, "WORKSHOP_USAGE");

                SMWorldRegion worldRegion = this.findWorkshopRegion(ctx.args.get(0));
                if (worldRegion == null) {
                    World world = Bukkit.getWorld("workshop_" + ctx.args.get(0));
                    if (world == null) {
                        ctx.returnErrorLocale("WORKSHOP_NOT_FOUND");
                    } else {
                        if (!SMLuckPerms.playerInGroup(ctx.player, "workshop_" + ctx.args.get(0).toLowerCase())) {
                            ctx.returnErrorLocale("WORKSHOP_NO_PERMISSION");
                        }

                        SMCommon.delayedPlayerTeleport(ctx.player, world.getSpawnLocation());
                    }
                } else {
                    if (!SMLuckPerms.playerInGroup(ctx.player, "workshop_" + ctx.args.get(0).toLowerCase())) {
                        ctx.returnErrorLocale("WORKSHOP_NO_PERMISSION");
                    }

                    Location center = worldRegion.center();
                    SMCommon.safePlayerTeleport(ctx.player, center);
                }
            })
            .register();

        return true;
    }

    public SMWorldRegion findWorkshopRegion(String searchString) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();

        for (World world : Bukkit.getWorlds()) {
            RegionManager regionManager = container.get(BukkitAdapter.adapt(world));

            if (regionManager != null) {
                for (ProtectedRegion region : regionManager.getRegions().values()) {
                    if (region.getId().matches("workshop_" + searchString)) {
                        return new SMWorldRegion(region, world);
                    }
                }
            }
        }

        return null; // No matching region found
    }

    private void checkRegionMove(Player player, Location from, Location to) {
        RegionManager oldRegionManager = container.get(BukkitAdapter.adapt(from.getWorld()));
        RegionManager newRegionManager = container.get(BukkitAdapter.adapt(to.getWorld()));

        if (oldRegionManager == null || newRegionManager == null)
            return;
        ApplicableRegionSet oldRegions =
            oldRegionManager.getApplicableRegions(BukkitAdapter.adapt(from).toVector().toBlockPoint());
        ApplicableRegionSet newRegions =
            newRegionManager.getApplicableRegions(BukkitAdapter.adapt(to).toVector().toBlockPoint());

        if (!oldRegions.getRegions().equals(newRegions.getRegions())) {
            // Check regions the player has left
            for (ProtectedRegion region : oldRegions) {
                if (!newRegions.getRegions().contains(region)) {
                    // Player left this region
                    exitRegion(player, region);
                }
            }

            // Check regions the player has entered
            for (ProtectedRegion region : newRegions) {
                if (!oldRegions.getRegions().contains(region)) {
                    // Player entered this region
                    enterRegion(player, region);
                }
            }
        }
    }

    private void enterRegion(Player player, ProtectedRegion region) {
        String regionId = region.getId();
        if (regionId.startsWith("workshop_")) {
            String workshopName = regionId.substring(9);
            String beautifiedName = SMCommon.beautifyCapitalize(workshopName);

            SMCommon.showGreeting(
                player,
                SMReplacer.replaceVariables(SMLocale.get(player.getLocale(), "WORKSHOP_ENTER_TITLE"), "workshop",
                    beautifiedName),
                SMReplacer.replaceVariables(SMLocale.get(player.getLocale(), "WORKSHOP_ENTER_SUBTITLE"), "workshop",
                    beautifiedName));

            if (SMLuckPerms.playerInGroup(player, regionId)) {
                SMLuckPerms.addGroup(player, regionId + "_active");
                SMMessenger.infoLocale(player, "WORKSHOP_ENTER_MEMBER", "workshop", beautifiedName);

                LocalPlayer wgPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
                if (wgPlayer != null) {
                    WorldGuard.getInstance().getPlatform().getSessionManager().resetState(wgPlayer);
                }
            } else {
                SMMessenger.infoLocale(player, "WORKSHOP_ENTER_NON_MEMBER", "workshop", beautifiedName);
            }
        }
    }

    private void exitRegion(Player player, ProtectedRegion region) {
        String regionId = region.getId();
        if (regionId.startsWith("workshop_")) {
            String workshopName = regionId.substring(9);

            SMMessenger.infoLocale(player, "WORKSHOP_EXIT", "workshop", SMCommon.beautifyCapitalize(workshopName));
            SMLuckPerms.removeGroup(player, regionId + "_active");
        }
    }
}
