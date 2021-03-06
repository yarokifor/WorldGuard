/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.internal.event;

import com.sk89q.worldguard.internal.cause.Cause;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Fired when an item is interacted with.
 */
public class ItemInteractEvent extends AbstractInteractEvent {

    private static final HandlerList handlers = new HandlerList();
    private final World world;
    private final ItemStack itemStack;

    /**
     * Create a new instance.
     *
     * @param originalEvent the original event
     * @param causes a list of causes, where the originating causes are at the beginning
     * @param interaction the action that is being taken
     * @param world the world
     * @param itemStack the item
     */
    public ItemInteractEvent(Event originalEvent, List<? extends Cause<?>> causes, Interaction interaction, World world, ItemStack itemStack) {
        super(originalEvent, causes, interaction);
        checkNotNull(world);
        checkNotNull(itemStack);
        this.world = world;
        this.itemStack = itemStack;
    }

    /**
     * Get the world.
     *
     * @return the world
     */
    public World getWorld() {
        return world;
    }

    /**
     * Get the item stack.
     *
     * @return the item stack
     */
    public ItemStack getItemStack() {
        return itemStack;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

}
