/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.modules.flag;

import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Flag;
import com.griefcraft.model.Protection;
import com.griefcraft.scripting.JavaModule;
import com.griefcraft.scripting.event.LWCMagnetPullEvent;
import com.griefcraft.util.EnumUtil;
import com.griefcraft.util.config.Configuration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class MagnetModule extends JavaModule {
    @SuppressWarnings("ExcessiveLambdaUsage")
    private static final EntityType ITEM_ENTITY_TYPE = EnumUtil
            .valueOf(EntityType.class, "DROPPED_ITEM") // 1.20.4 and prior
            .orElseGet(() -> EntityType.ITEM); // 1.20.5 and above

    private Configuration configuration = Configuration.load("magnet.yml");

    /**
     * If this module is enabled
     */
    private boolean enabled = false;

    /**
     * The item blacklist
     */
    private List<Material> itemBlacklist;

    /**
     * The radius around the container in which to suck up items
     */
    private int radius;

    /**
     * How many items to check each time
     */
    private int perSweep;

    /**
     * Maximum pickup delay of items to suck up
     */
    private int maxPickupDelay;

    /**
     * The current entity queue
     */
    private final Queue<MagnetNode> items = new LinkedList<>();

    private class MagnetNode {
        Item item;
        Protection protection;
    }

    // does all of the work
    // searches the worlds for items and magnet chests nearby
    private class MagnetTask implements Runnable {
        public void run() {
            Server server = Bukkit.getServer();
            LWC lwc = LWC.getInstance();

            // Do we need to requeue?
            if (items.size() == 0) {
                for (World world : server.getWorlds()) {
                    for (Entity entity : world.getEntities()) {
                        if (isDisplay(entity)) {
                            continue;
                        }

                        if (!(entity instanceof Item)) {
                            continue;
                        }

                        Item item = (Item) entity;
                        ItemStack stack = item.getItemStack();

                        // check if the pickup delay is ok
                        if (item.getPickupDelay() > maxPickupDelay) {
                            continue;
                        }

                        // check if it is in the blacklist
                        if (itemBlacklist.contains(stack.getType())) {
                            continue;
                        }

                        // check if the item is valid
                        if (stack.getAmount() <= 0) {
                            continue;
                        }

                        if (item.isDead()) {
                            continue;
                        }

                        LWCMagnetPullEvent event = new LWCMagnetPullEvent(item);
                        lwc.getModuleLoader().dispatchEvent(event);

                        // has the event been cancelled?
                        if (event.isCancelled()) {
                            continue;
                        }

                        Location location = item.getLocation();
                        int x = location.getBlockX();
                        int y = location.getBlockY();
                        int z = location.getBlockZ();

                        List<Protection> protections = lwc.getPhysicalDatabase().loadProtections(world.getName(), x, y,
                                z, radius);
                        for (Protection protection : protections) {
                            if (!protection.hasFlag(Flag.Type.MAGNET)) {
                                continue;
                            }

                            if (protection.getBukkitWorld().getName() != item.getWorld().getName())
                                continue;

                            // we only want container blocks
                            if (!(protection.getBlock().getState() instanceof Container)) {
                                continue;
                            }

                            // never allow a shulker box to enter another shulker box
                            if (item.getItemStack().getType().toString().contains("SHULKER_BOX") && protection.getBlock().getType().toString().contains("SHULKER_BOX")) {
                                continue;
                            }

                            // don't try to enter a full container
                            boolean isFull = lwc.getMaxDepositAmount(protection.getBlock(), stack) == 0;
                            if (isFull) {
                                continue;
                            }

                            MagnetNode node = new MagnetNode();
                            node.item = item;
                            node.protection = protection;
                            items.offer(node);
                            break;
                        }
                    }
                }
            }

            // Throttle amount of items polled
            int count = 0;
            MagnetNode node;

            while ((node = items.poll()) != null) {
                Item item = node.item;
                Protection protection = node.protection;

                World world = item.getWorld();
                ItemStack itemStack = item.getItemStack();
                Location location = item.getLocation();
                Block block = protection.getBlock();

                if (item.isDead()) {
                    continue;
                }

                // Remove the items and suck them up :3
                Map<Integer, ItemStack> remaining;
                try {
                    remaining = lwc.depositItems(block, itemStack);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                // reached deposit limit, leave the item intact
                if (remaining == null) {
                    continue;
                }

                if (remaining.size() == 1) {
                    ItemStack other = remaining.values().iterator().next();

                    if (itemStack.getType() == other.getType() && itemStack.getAmount() == other.getAmount()) {
                        continue;
                    }
                }
                // remove the item on the ground
                item.remove();

                // if we have a remainder, we need to drop them
                if (remaining.size() > 0) {
                    for (ItemStack stack : remaining.values()) {
                        world.dropItemNaturally(location, stack);
                    }
                }

                if (count > perSweep) {
                    break;
                }

                count++;
            }

        }
    }

    public static boolean isDisplay(Entity entity) {
        try {
            if (entity.getType() == ITEM_ENTITY_TYPE) {
                ItemMeta itemMeta = ((Item) entity).getItemStack().getItemMeta();
                if (itemMeta != null && containsLocation(itemMeta.getDisplayName())) {
                    return true;
                }
            } else if (entity.getType() == EntityType.ARMOR_STAND) {
                if (containsLocation(entity.getCustomName())) {
                    return true;
                }
            }
        } catch (NoSuchFieldError error) {
            // do nothing
        }
        return false;
    }

    public static boolean containsLocation(String s) {
        if (s == null)
            return false;
        if (s.startsWith("***{")) {
            if ((s.indexOf(',') != s.lastIndexOf(',')) && s.indexOf('}') != -1)
                return true;
        }
        return false;
    }

    @Override
    public void load(LWC lwc) {
        enabled = configuration.getBoolean("magnet.enabled", false);
        itemBlacklist = new ArrayList<Material>();
        radius = configuration.getInt("magnet.radius", 3);
        perSweep = configuration.getInt("magnet.perSweep", 20);
        maxPickupDelay = configuration.getInt("magnet.maxPickupDelay", 40);

        if (!enabled) {
            return;
        }

        // get the item blacklist
        List<String> temp = configuration.getStringList("magnet.blacklist", new ArrayList<String>());

        for (String item : temp) {
            Material material = Material.matchMaterial(item);

            if (material != null) {
                itemBlacklist.add(material);
            }
        }

        // register our search thread schedule
        MagnetTask searchThread = new MagnetTask();
        lwc.getPlugin().getServer().getScheduler().scheduleSyncRepeatingTask(lwc.getPlugin(), searchThread, 50, 50);
    }

}
