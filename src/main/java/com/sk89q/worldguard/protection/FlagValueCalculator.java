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

package com.sk89q.worldguard.protection;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Calculates the value of a flag given a list of regions and an optional
 * global region.
 *
 * <p>Since there may be multiple overlapping regions, regions with
 * differing priorities, regions with inheritance, flags with region groups
 * assigned to them, and much more, the task of calculating the "effective"
 * value of a flag is far from trivial. This class abstracts away the
 * difficult with a number of methods for performing these calculations.</p>
 */
public class FlagValueCalculator {

    private final SortedSet<ProtectedRegion> applicable;
    @Nullable
    private final ProtectedRegion globalRegion;

    /**
     * Create a new instance.
     *
     * @param applicable a list of applicable regions
     * @param globalRegion an optional global region (null to not use one)
     */
    public FlagValueCalculator(SortedSet<ProtectedRegion> applicable, @Nullable ProtectedRegion globalRegion) {
        checkNotNull(applicable);

        this.applicable = applicable;
        this.globalRegion = globalRegion;
    }

    /**
     * Return the membership status of the given player, indicating
     * whether there are no (counted) regions in the list of regions,
     * whether the player is a member of all regions, or whether
     * the region is not a member of all regions.
     *
     * <p>A region is "counted" if it doesn't have the
     * {@link DefaultFlag#PASSTHROUGH} flag set to {@code ALLOW}. (The
     * explicit purpose of the PASSTHROUGH flag is to have the region
     * be skipped over in this check.)</p>
     *
     * <p>This method is mostly for internal use. It's not particularly
     * useful.</p>
     *
     * @param player the player
     * @return the membership result
     */
    public Result getMembership(LocalPlayer player) {
        checkNotNull(player);

        int minimumPriority = Integer.MIN_VALUE;
        boolean foundApplicableRegion = false;

        // Say there are two regions in one location: CHILD and PARENT (CHILD
        // is a child of PARENT). If there are two overlapping regions in WG, a
        // player has to be a member of /both/ (or flags permit) in order to
        // build in that location. However, inheritance is supposed
        // to allow building if the player is a member of just CHILD. That
        // presents a problem.
        //
        // To rectify this, we keep two sets. When we iterate over the list of
        // regions, there are two scenarios that we may encounter:
        //
        // 1) PARENT first, CHILD later:
        //    a) When the loop reaches PARENT, PARENT is added to needsClear.
        //    b) When the loop reaches CHILD, parents of CHILD (which includes
        //       PARENT) are removed from needsClear.
        //    c) needsClear is empty again.
        //
        // 2) CHILD first, PARENT later:
        //    a) When the loop reaches CHILD, CHILD's parents (i.e. PARENT) are
        //       added to hasCleared.
        //    b) When the loop reaches PARENT, since PARENT is already in
        //       hasCleared, it does not add PARENT to needsClear.
        //    c) needsClear stays empty.
        //
        // As long as the process ends with needsClear being empty, then
        // we have satisfied all membership requirements.

        Set<ProtectedRegion> needsClear = new HashSet<ProtectedRegion>();
        Set<ProtectedRegion> hasCleared = new HashSet<ProtectedRegion>();

        for (ProtectedRegion region : applicable) {
            // Don't consider lower priorities below minimumPriority
            // (which starts at Integer.MIN_VALUE). A region that "counts"
            // (has the flag set OR has members) will raise minimumPriority
            // to its own priority.
            if (region.getPriority() < minimumPriority) {
                break;
            }

            // If PASSTHROUGH is set, ignore this region
            if (getEffectiveFlag(region, DefaultFlag.PASSTHROUGH, player) == State.ALLOW) {
                continue;
            }

            minimumPriority = region.getPriority();
            foundApplicableRegion = true;

            if (!hasCleared.contains(region)) {
                if (!region.isMember(player)) {
                    needsClear.add(region);
                } else {
                    // Need to clear all parents
                    removeParents(needsClear, hasCleared, region);
                }
            }
        }

        if (foundApplicableRegion) {
            return needsClear.isEmpty() ? Result.SUCCESS : Result.FAIL;
        } else {
            return Result.NO_REGIONS;
        }
    }

    /**
     * Test whether the given player is permitted to place, break, or
     * modify any block, entity, or other object. A list of flags is to be
     * provided (one of which should probably be {@link DefaultFlag#BUILD})
     * so that the calculation can consider all of those flags.
     *
     * <p>For example, if we are checking for the ability to interact
     * with a chest, we would want to give permission if (1) the player is
     * a member of the region, (2) the {@code build} flag is set to
     * {@code ALLOW}, or (3) the {@code chest-access} flag is set to
     * {@code ALLOW}. However, if any of the two flags are set
     * to {@code DENY}, that must override everything else and deny access.</p>
     *
     * <p>This method handles that example perfectly. To use the method for
     * the example, the call would look like this:</p>
     *
     * <pre>testPermission(player, DefaultFlag.BUILD, DefaultFlag.CHEST_ACCESS)</pre>
     *
     * @param player the player
     * @param flags zero or more flags
     * @return true if permission is granted
     */
    public State testPermission(LocalPlayer player, StateFlag... flags) {
        checkNotNull(player);
        checkNotNull(flags);

        // Legacy behavior dictates that the global region is really a
        // "wilderness" region. It has no effect when there are one or more
        // regions without the PASSTHROUGH flag set.
        //
        // In addition, the global region can never override any PASSTHROUGH
        // region.
        //
        // Lastly, if the global region has members, then permission will
        // be denied by default except to those members that are a part of
        // the global region, turning the global region into a region itself
        // that covers the entire world. Unfortunately, this is really a hack
        // and we support it for legacy reasons.

        switch (getMembership(player)) {
            case SUCCESS:
                return StateFlag.combine(getState(player, flags), State.ALLOW);
            case FAIL:
                return getState(player, flags);
            case NO_REGIONS:
                if (globalRegion != null && globalRegion.hasMembersOrOwners()) {
                    if (globalRegion.isMember(player)) {
                        return StateFlag.combine(getState(player, flags), State.ALLOW);
                    } else {
                        State value = null;

                        for (StateFlag flag : flags) {
                            value = StateFlag.combine(value,globalRegion.getFlag(flag));
                            if (value == State.DENY) {
                                break;
                            }
                        }

                        return value;
                    }
                }
            default:
                return getStateWithFallback(player, flags);
        }
    }

    /**
     * Get the effective value for a list of state flags. The rules of
     * states is observed here; that is, {@code DENY} overrides {@code ALLOW},
     * and {@code ALLOW} overrides {@code NONE}. This method will check
     * the global region and {@link Flag#getDefault()} (in that order) if
     * a value for the flag is not set in any region.
     *
     * <p>This method does <strong>not</strong> properly process build
     * permissions. Instead, use {@link #testPermission(LocalPlayer, StateFlag...)}
     * for that purpose. This method is ideal for testing non-build related
     * state flags (although a rarity), an example of which would be whether
     * to play a song to players that enter an area.</p>
     *
     * <p>A player can be provided that is used to determine whether the value
     * of a flag on a particular region should be used. For example, if a
     * flag's region group is set to {@link RegionGroup#MEMBERS} and the given
     * player is not a member, then the region would be skipped when
     * querying that flag. If {@code null} is provided for the player, then
     * only flags that use {@link RegionGroup#ALL},
     * {@link RegionGroup#NON_MEMBERS}, etc. will apply.</p>
     *
     * @param player an optional player, which would be used to determine the region group to apply
     * @param flags a list of flags to check
     * @return a state
     */
    @Nullable
    public State getStateWithFallback(@Nullable LocalPlayer player, StateFlag... flags) {
        State value = null;

        for (StateFlag flag : flags) {
            value = StateFlag.combine(value, getSingleValueWithFallback(player, flag));
            if (value == State.DENY) {
                break;
            }
        }

        return value;
    }


    /**
     * Get the effective value for a list of state flags. The rules of
     * states is observed here; that is, {@code DENY} overrides {@code ALLOW},
     * and {@code ALLOW} overrides {@code NONE}. This method does not check
     * the global region and ignores a flag's default value.
     *
     * <p>This method does <strong>not</strong> properly process build
     * permissions. Instead, use {@link #testPermission(LocalPlayer, StateFlag...)}
     * for that purpose. This method is ideal for testing non-build related
     * state flags (although a rarity), an example of which would be whether
     * to play a song to players that enter an area.</p>
     *
     * <p>A player can be provided that is used to determine whether the value
     * of a flag on a particular region should be used. For example, if a
     * flag's region group is set to {@link RegionGroup#MEMBERS} and the given
     * player is not a member, then the region would be skipped when
     * querying that flag. If {@code null} is provided for the player, then
     * only flags that use {@link RegionGroup#ALL},
     * {@link RegionGroup#NON_MEMBERS}, etc. will apply.</p>
     *
     * @param player an optional player, which would be used to determine the region group to apply
     * @param flags a list of flags to check
     * @return a state
     */
    @Nullable
    public State getState(@Nullable LocalPlayer player, StateFlag... flags) {
        State value = null;

        for (StateFlag flag : flags) {
            value = StateFlag.combine(value, getSingleValue(player, flag));
            if (value == State.DENY) {
                break;
            }
        }

        return value;
    }

    /**
     * Get the effective value for a flag. If there are multiple values
     * (for example, if there are multiple regions with the same priority
     * but with different farewell messages set, there would be multiple
     * completing values), then the selected (or "winning") value will depend
     * on the flag type. This method will check the global region
     * for a value as well as the flag's default value.
     *
     * <p>Only some flag types actually have a strategy for picking the
     * "best value." For most types, the actual value that is chosen to be
     * returned is undefined (it could be any value). As of writing, the only
     * type of flag that can consistently return the same 'best' value is
     * {@link StateFlag}.</p>
     *
     * <p>This method does <strong>not</strong> properly process build
     * permissions. Instead, use {@link #testPermission(LocalPlayer, StateFlag...)}
     * for that purpose.</p>
     *
     * <p>A player can be provided that is used to determine whether the value
     * of a flag on a particular region should be used. For example, if a
     * flag's region group is set to {@link RegionGroup#MEMBERS} and the given
     * player is not a member, then the region would be skipped when
     * querying that flag. If {@code null} is provided for the player, then
     * only flags that use {@link RegionGroup#ALL},
     * {@link RegionGroup#NON_MEMBERS}, etc. will apply.</p>
     *
     * @param player an optional player, which would be used to determine the region group to apply
     * @param flag the flag
     * @return a value, which could be {@code null}
     * @see #getSingleValue(LocalPlayer, Flag) does not check global region, defaults
     */
    @Nullable
    public <V> V getSingleValueWithFallback(@Nullable LocalPlayer player, Flag<V> flag) {
        checkNotNull(flag);

        V value = getSingleValue(player, flag);

        if (value != null) {
            return value;
        }

        // Get the value from the global region
        if (globalRegion != null) {
            value = globalRegion.getFlag(flag);
        }

        // Still no value? Check the default value for the flag
        if (value == null) {
            value = flag.getDefault();
        }

        return flag.validateDefaultValue(value);
    }

    /**
     * Get the effective value for a flag. If there are multiple values
     * (for example, if there are multiple regions with the same priority
     * but with different farewell messages set, there would be multiple
     * completing values), then the selected (or "winning") value will depend
     * on the flag type. This method never checks the global region or
     * the flag's default value.
     *
     * <p>Only some flag types actually have a strategy for picking the
     * "best value." For most types, the actual value that is chosen to be
     * returned is undefined (it could be any value). As of writing, the only
     * type of flag that can consistently return the same 'best' value is
     * {@link StateFlag}.</p>
     *
     * <p>This method does <strong>not</strong> properly process build
     * permissions. Instead, use {@link #testPermission(LocalPlayer, StateFlag...)}
     * for that purpose.</p>
     *
     * <p>A player can be provided that is used to determine whether the value
     * of a flag on a particular region should be used. For example, if a
     * flag's region group is set to {@link RegionGroup#MEMBERS} and the given
     * player is not a member, then the region would be skipped when
     * querying that flag. If {@code null} is provided for the player, then
     * only flags that use {@link RegionGroup#ALL},
     * {@link RegionGroup#NON_MEMBERS}, etc. will apply.</p>
     *
     * @param player an optional player, which would be used to determine the region group to apply
     * @param flag the flag
     * @return a value, which could be {@code null}
     * @see #getSingleValueWithFallback(LocalPlayer, Flag) checks global regions, defaults
     */
    @Nullable
    public <V> V getSingleValue(@Nullable LocalPlayer player, Flag<V> flag) {
        Collection<V> values = getValues(player, flag);
        return flag.chooseValue(values);
    }

    /**
     * Get the effective values for a flag, returning a collection of all
     * values. It is up to the caller to determine which value, if any,
     * from the collection will be used.
     *
     * <p>This method does <strong>not</strong> properly process build
     * permissions. Instead, use {@link #testPermission(LocalPlayer, StateFlag...)}
     * for that purpose.</p>
     *
     * <p>A player can be provided that is used to determine whether the value
     * of a flag on a particular region should be used. For example, if a
     * flag's region group is set to {@link RegionGroup#MEMBERS} and the given
     * player is not a member, then the region would be skipped when
     * querying that flag. If {@code null} is provided for the player, then
     * only flags that use {@link RegionGroup#ALL},
     * {@link RegionGroup#NON_MEMBERS}, etc. will apply.</p>
     */
    public <V> Collection<V> getValues(@Nullable LocalPlayer player, Flag<V> flag) {
        checkNotNull(flag);

        int minimumPriority = Integer.MIN_VALUE;

        // Say there are two regions in one location: CHILD and PARENT (CHILD
        // is a child of PARENT). If the two are overlapping regions in WG,
        // both with values set, then we have a problem. Due to inheritance,
        // only the CHILD's value for the flag should be used because it
        // overrides its parent's value, but default behavior is to collect
        // all the values into a list.
        //
        // To rectify this, we keep a map of consideredValues (region -> value)
        // and an ignoredRegions set. When we iterate over the list of
        // regions, there are two scenarios that we may encounter:
        //
        // 1) PARENT first, CHILD later:
        //    a) When the loop reaches PARENT, PARENT's value is added to
        //       consideredValues
        //    b) When the loop reaches CHILD, parents of CHILD (which includes
        //       PARENT) are removed from consideredValues (so we no longer
        //       consider those values). The CHILD's value is then added to
        //       consideredValues.
        //    c) In the end, only CHILD's value exists in consideredValues.
        //
        // 2) CHILD first, PARENT later:
        //    a) When the loop reaches CHILD, CHILD's value is added to
        //       consideredValues. In addition, the CHILD's parents (which
        //       includes PARENT) are added to ignoredRegions.
        //    b) When the loop reaches PARENT, since PARENT is in
        //       ignoredRegions, the parent is skipped over.
        //    c) In the end, only CHILD's value exists in consideredValues.

        Map<ProtectedRegion, V> consideredValues = new HashMap<ProtectedRegion, V>();
        Set<ProtectedRegion> ignoredRegions = new HashSet<ProtectedRegion>();

        for (ProtectedRegion region : applicable) {
            // Don't consider lower priorities below minimumPriority
            // (which starts at Integer.MIN_VALUE). A region that "counts"
            // (has the flag set) will raise minimumPriority to its own
            // priority.
            if (region.getPriority() < minimumPriority) {
                break;
            }

            V value = getEffectiveFlag(region, flag, player);

            if (value != null) {
                if (!ignoredRegions.contains(region)) {
                    minimumPriority = region.getPriority();

                    ignoreValuesOfParents(consideredValues, ignoredRegions, region);
                    consideredValues.put(region, value);

                    if (value == State.DENY) {
                        // Since DENY overrides all other values, there
                        // is no need to consider any further regions
                        break;
                    }
                }
            }
        }

        return consideredValues.values();
    }

    /**
     * Get a region's state flag, checking parent regions until a value for the
     * flag can be found (if one even exists).
     *
     * @param region the region
     * @param flag the flag
     * @return the value
     */
    public <V> V getEffectiveFlag(final ProtectedRegion region, Flag<V> flag, @Nullable LocalPlayer player) {
        ProtectedRegion current = region;

        while (current != null) {
            V value = current.getFlag(flag);
            boolean use = true;

            if (flag.getRegionGroupFlag() != null) {
                RegionGroup group = current.getFlag(flag.getRegionGroupFlag());
                if (group == null) {
                    group = flag.getRegionGroupFlag().getDefault();
                }

                if (!RegionGroupFlag.isMember(region, group, player)) {
                    use = false;
                }
            }

            if (use && value != null) {
                return value;
            }

            current = current.getParent();
        }

        return null;
    }

    /**
     * Clear a region's parents for isFlagAllowed().
     *
     * @param needsClear the regions that should be cleared
     * @param hasCleared the regions already cleared
     * @param region the region to start from
     */
    private void removeParents(Set<ProtectedRegion> needsClear, Set<ProtectedRegion> hasCleared, ProtectedRegion region) {
        ProtectedRegion parent = region.getParent();

        while (parent != null) {
            if (!needsClear.remove(parent)) {
                hasCleared.add(parent);
            }

            parent = parent.getParent();
        }
    }

    /**
     * Clear a region's parents for getFlag().
     *
     * @param needsClear The regions that should be cleared
     * @param hasCleared The regions already cleared
     * @param region The region to start from
     */
    private void ignoreValuesOfParents(Map<ProtectedRegion, ?> needsClear, Set<ProtectedRegion> hasCleared, ProtectedRegion region) {
        ProtectedRegion parent = region.getParent();

        while (parent != null) {
            if (needsClear.remove(parent) == null) {
                hasCleared.add(parent);
            }

            parent = parent.getParent();
        }
    }

    /**
     * Describes the membership result from
     * {@link #getMembership(LocalPlayer)}.
     */
    public static enum Result {
        /**
         * Indicates that there are no regions or the only regions are
         * ones with {@link DefaultFlag#PASSTHROUGH} enabled.
         */
        NO_REGIONS,

        /**
         * Indicates that the player is not a member of all overlapping
         * regions.
         */
        FAIL,

        /**
         * Indicates that the player is a member of all overlapping
         * regions.
         */
        SUCCESS
    }

}