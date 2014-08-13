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

package com.sk89q.worldguard.bukkit.commands;

import com.sk89q.worldguard.protection.managers.RegionManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkNotNull;

class RegionManagerSave implements Callable<Collection<RegionManager>> {

    private final Collection<RegionManager> managers;

    RegionManagerSave(Collection<RegionManager> managers) {
        checkNotNull(managers);
        this.managers = managers;
    }

    RegionManagerSave(RegionManager... manager) {
        this(Arrays.asList(manager));
    }

    @Override
    public Collection<RegionManager> call() throws IOException {
        for (RegionManager manager : managers) {
            manager.save();
        }

        return managers;
    }

}