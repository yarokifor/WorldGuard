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

package com.sk89q.worldguard.protection.databases;


import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.BlockVector2D;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldguard.bukkit.ConfigurationManager;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLDatabase extends AbstractProtectionDatabase {
    private final Logger logger;

    private Yaml yaml;

    private Map<String, ProtectedRegion> regions;

    private Map<String, ProtectedRegion> cuboidRegions;
    private Map<String, ProtectedRegion> poly2dRegions;
    private Map<String, ProtectedRegion> globalRegions;
    private Map<ProtectedRegion, String> parentSets;

    private final ConfigurationManager config;

    private Connection conn;
    private int worldDbId = -1; // The database will never have an id of -1;

    public MySQLDatabase(ConfigurationManager config, String world, Logger logger) throws ProtectionDatabaseException {
        this.config = config;
        String world1 = world;
        this.logger = logger;

        PreparedStatement worldStmt = null;
        ResultSet worldResult = null;
        PreparedStatement insertWorldStatement = null;
        try {
            connect();

            PreparedStatement verTest = null;
            try {
                // Test if the database is up to date, if not throw a critical error
                verTest = this.conn.prepareStatement(
                        "SELECT `world_id` FROM `" + config.sqlTablePrefix + "region_cuboid` LIMIT 0,1;"
                );
                verTest.execute();
            } catch (SQLException ex) {
                throw new InvalidTableFormatException(
                        "region_storage_update_20110325.sql"
                );
            } finally {
                closeResource(verTest);
            }

            worldStmt = conn.prepareStatement(
                    "SELECT `id` FROM " +
                    "`" + config.sqlTablePrefix + "world` " +
                    "WHERE `name` = ? LIMIT 0,1"
            );

            worldStmt.setString(1, world1);
            worldResult = worldStmt.executeQuery();

            if (worldResult.first()) {
                this.worldDbId = worldResult.getInt("id");
            } else {
                insertWorldStatement = this.conn.prepareStatement(
                        "INSERT INTO " +
                        "`" + config.sqlTablePrefix + "world` " +
                        "(`id`, `name`) VALUES (null, ?)",
                        Statement.RETURN_GENERATED_KEYS
                );

                insertWorldStatement.setString(1, world);
                insertWorldStatement.execute();
                ResultSet generatedKeys = insertWorldStatement.getGeneratedKeys();
                if (generatedKeys.first()) {
                    this.worldDbId = generatedKeys.getInt(1);
                }
            }
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, ex.getMessage(), ex);
            // We havn't connected to the databases, or there was an error
            // initialising the world record, so there is no point continuing
            return;
        } finally {
            closeResource(worldResult);
            closeResource(worldStmt);
            closeResource(insertWorldStatement);
        }

        if (this.worldDbId <= 0) {
            logger.log(Level.SEVERE, "Could not find or create the world");
            // There was an error initialising the world record, so there is
            // no point continuing
            return;
        }

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setDefaultFlowStyle(FlowStyle.FLOW);
        Representer representer = new Representer();
        representer.setDefaultFlowStyle(FlowStyle.FLOW);

        // We have to use this in order to properly save non-string values
        yaml = new Yaml(new SafeConstructor(), new Representer(), options);
    }

    private void connect() throws SQLException {
        if (conn != null) {
            // Check if the connection is still alive/valid.
            try {
                conn.isValid(2);
            } catch (SQLException ex) {
                // Test if validation failed because the connection is dead,
                // and if it is mark the connection as closed (the MySQL Driver
                // does not ensure that the connection is marked as closed unless
                // the close() method has been called.
                if ("08S01".equals(ex.getSQLState())) {
                    conn.close();
                }
            }
        }
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(config.sqlDsn, config.sqlUsername, config.sqlPassword);
        }
    }

    private void loadFlags(ProtectedRegion region) {
        // @TODO: Iterate _ONCE_
        PreparedStatement flagsStatement = null;
        ResultSet flagsResultSet = null;
        try {
            flagsStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`region_flag`.`flag`, " +
                    "`region_flag`.`value` " +
                    "FROM `" + config.sqlTablePrefix + "region_flag` AS `region_flag` " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId
            );

            flagsStatement.setString(1, region.getId().toLowerCase());
            flagsResultSet = flagsStatement.executeQuery();

            Map<String,Object> regionFlags = new HashMap<String,Object>();
            while (flagsResultSet.next()) {
                regionFlags.put(
                        flagsResultSet.getString("flag"),
                        sqlUnmarshal(flagsResultSet.getString("value"))
                        );
            }

            // @TODO: Make this better
            for (Flag<?> flag : DefaultFlag.getFlags()) {
                Object o = regionFlags.get(flag.getName());
                if (o != null) {
                    setFlag(region, flag, o);
                }
            }
        } catch (SQLException ex) {
            logger.warning(
                    "Unable to load flags for region "
                    + region.getId().toLowerCase() + ": " + ex.getMessage()
            );
        } finally {
            closeResource(flagsResultSet);
            closeResource(flagsStatement);
        }
    }

    private <T> void setFlag(ProtectedRegion region, Flag<T> flag, Object rawValue) {
        T val = flag.unmarshal(rawValue);
        if (val == null) {
            logger.warning("Failed to parse flag '" + flag.getName()
                    + "' with value '" + rawValue.toString() + "'");
            return;
        }
        region.setFlag(flag, val);
    }

    private void loadOwnersAndMembers(ProtectedRegion region) {
        DefaultDomain owners = new DefaultDomain();
        DefaultDomain members = new DefaultDomain();

        ResultSet userSet = null;
        PreparedStatement usersStatement = null;
        try {
            usersStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`user`.`name`, " +
                    "`region_players`.`owner` " +
                    "FROM `" + config.sqlTablePrefix + "region_players` AS `region_players` " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "user` AS `user` ON ( " +
                    "`region_players`.`user_id` = " +
                    "`user`.`id`) " +
                    "WHERE `region_players`.`region_id` = ? " +
                    "AND `region_players`.`world_id` = " + this.worldDbId
            );

            usersStatement.setString(1, region.getId().toLowerCase());
            userSet = usersStatement.executeQuery();
            while(userSet.next()) {
                if (userSet.getBoolean("owner")) {
                    owners.addPlayer(userSet.getString("name"));
                } else {
                    members.addPlayer(userSet.getString("name"));
                }
            }
        } catch (SQLException ex) {
            logger.warning("Unable to load users for region " + region.getId().toLowerCase() + ": " + ex.getMessage());
        } finally {
            closeResource(userSet);
            closeResource(usersStatement);
        }

        PreparedStatement groupsStatement = null;
        ResultSet groupSet = null;
        try {
            groupsStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`group`.`name`, " +
                    "`region_groups`.`owner` " +
                    "FROM `" + config.sqlTablePrefix + "region_groups` AS `region_groups` " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "group` AS `group` ON ( " +
                    "`region_groups`.`group_id` = " +
                    "`group`.`id`) " +
                    "WHERE `region_groups`.`region_id` = ? " +
                    "AND `region_groups`.`world_id` = " + this.worldDbId
            );

            groupsStatement.setString(1, region.getId().toLowerCase());
            groupSet = groupsStatement.executeQuery();
            while(groupSet.next()) {
                if (groupSet.getBoolean("owner")) {
                    owners.addGroup(groupSet.getString("name"));
                } else {
                    members.addGroup(groupSet.getString("name"));
                }
            }
        } catch (SQLException ex) {
            logger.warning("Unable to load groups for region " + region.getId().toLowerCase() + ": " + ex.getMessage());
        } finally {
            closeResource(groupSet);
            closeResource(groupsStatement);
        }

        region.setOwners(owners);
        region.setMembers(members);
    }

    private void loadGlobal() {
        Map<String,ProtectedRegion> regions =
                new HashMap<String,ProtectedRegion>();

        PreparedStatement globalRegionStatement = null;
        ResultSet globalResultSet = null;
        try {
            globalRegionStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`region`.`id`, " +
                    "`region`.`priority`, " +
                    "`parent`.`id` AS `parent` " +
                    "FROM `" + config.sqlTablePrefix + "region` AS `region` " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "region` AS `parent` " +
                    "ON (`region`.`parent` = `parent`.`id` " +
                    "AND `region`.`world_id` = `parent`.`world_id`) " +
                    "WHERE `region`.`type` = 'global' " +
                    "AND `region`.`world_id` = ? "
            );

            globalRegionStatement.setInt(1, this.worldDbId);
            globalResultSet = globalRegionStatement.executeQuery();

            while (globalResultSet.next()) {
                ProtectedRegion region = new GlobalProtectedRegion(globalResultSet.getString("id"));

                region.setPriority(globalResultSet.getInt("priority"));

                this.loadFlags(region);
                this.loadOwnersAndMembers(region);

                regions.put(globalResultSet.getString("id"), region);

                String parentId = globalResultSet.getString("parent");
                if (parentId != null) {
                    parentSets.put(region, parentId);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            logger.warning("Unable to load regions from sql database: " + ex.getMessage());
            Throwable t = ex.getCause();
            while (t != null) {
                logger.warning("\t\tCause: " + t.getMessage());
                t = t.getCause();
            }
        } finally {
            closeResource(globalResultSet);
            closeResource(globalRegionStatement);
        }

        globalRegions = regions;
    }

    private void loadCuboid() {
        Map<String,ProtectedRegion> regions =
                new HashMap<String,ProtectedRegion>();

        PreparedStatement cuboidRegionStatement = null;
        ResultSet cuboidResultSet = null;
        try {
            cuboidRegionStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`region_cuboid`.`min_z`, " +
                    "`region_cuboid`.`min_y`, " +
                    "`region_cuboid`.`min_x`, " +
                    "`region_cuboid`.`max_z`, " +
                    "`region_cuboid`.`max_y`, " +
                    "`region_cuboid`.`max_x`, " +
                    "`region`.`id`, " +
                    "`region`.`priority`, " +
                    "`parent`.`id` AS `parent` " +
                    "FROM `" + config.sqlTablePrefix + "region_cuboid` AS `region_cuboid` " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "region` AS `region` " +
                    "ON (`region_cuboid`.`region_id` = `region`.`id` " +
                    "AND `region_cuboid`.`world_id` = `region`.`world_id`) " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "region` AS `parent` " +
                    "ON (`region`.`parent` = `parent`.`id` " +
                    "AND `region`.`world_id` = `parent`.`world_id`) " +
                    "WHERE `region`.`world_id` = ? "
            );

            cuboidRegionStatement.setInt(1, this.worldDbId);
            cuboidResultSet = cuboidRegionStatement.executeQuery();

            while (cuboidResultSet.next()) {
                Vector pt1 = new Vector(
                        cuboidResultSet.getInt("min_x"),
                        cuboidResultSet.getInt("min_y"),
                        cuboidResultSet.getInt("min_z")
                );
                Vector pt2 = new Vector(
                        cuboidResultSet.getInt("max_x"),
                        cuboidResultSet.getInt("max_y"),
                        cuboidResultSet.getInt("max_z")
                );

                BlockVector min = Vector.getMinimum(pt1, pt2).toBlockVector();
                BlockVector max = Vector.getMaximum(pt1, pt2).toBlockVector();
                ProtectedRegion region = new ProtectedCuboidRegion(
                        cuboidResultSet.getString("id"),
                        min,
                        max
                );

                region.setPriority(cuboidResultSet.getInt("priority"));

                this.loadFlags(region);
                this.loadOwnersAndMembers(region);

                regions.put(cuboidResultSet.getString("id"), region);

                String parentId = cuboidResultSet.getString("parent");
                if (parentId != null) {
                    parentSets.put(region, parentId);
                }
            }

        } catch (SQLException ex) {
            ex.printStackTrace();
            logger.warning("Unable to load regions from sql database: " + ex.getMessage());
            Throwable t = ex.getCause();
            while (t != null) {
                logger.warning("\t\tCause: " + t.getMessage());
                t = t.getCause();
            }
        } finally {
            closeResource(cuboidResultSet);
            closeResource(cuboidRegionStatement);
        }

        cuboidRegions = regions;
    }

    private void loadPoly2d() {
        Map<String,ProtectedRegion> regions =
                new HashMap<String,ProtectedRegion>();

        PreparedStatement poly2dRegionStatement = null;
        ResultSet poly2dResultSet = null;
        PreparedStatement poly2dVectorStatement = null;
        try {
            poly2dRegionStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`region_poly2d`.`min_y`, " +
                    "`region_poly2d`.`max_y`, " +
                    "`region`.`id`, " +
                    "`region`.`priority`, " +
                    "`parent`.`id` AS `parent` " +
                    "FROM `" + config.sqlTablePrefix + "region_poly2d` AS `region_poly2d` " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "region` AS `region` " +
                    "ON (`region_poly2d`.`region_id` = `region`.`id` " +
                    "AND `region_poly2d`.`world_id` = `region`.`world_id`) " +
                    "LEFT JOIN `" + config.sqlTablePrefix + "region` AS `parent` " +
                    "ON (`region`.`parent` = `parent`.`id` " +
                    "AND `region`.`world_id` = `parent`.`world_id`) " +
                    "WHERE `region`.`world_id` = ? "
            );

            poly2dRegionStatement.setInt(1, this.worldDbId);
            poly2dResultSet = poly2dRegionStatement.executeQuery();

            poly2dVectorStatement = this.conn.prepareStatement(
                    "SELECT " +
                    "`region_poly2d_point`.`x`, " +
                    "`region_poly2d_point`.`z` " +
                    "FROM `" + config.sqlTablePrefix + "region_poly2d_point` AS `region_poly2d_point` " +
                    "WHERE `region_poly2d_point`.`region_id` = ? " +
                    "AND `region_poly2d_point`.`world_id` = " + this.worldDbId
            );

            while (poly2dResultSet.next()) {
                String id = poly2dResultSet.getString("id");

                Integer minY = poly2dResultSet.getInt("min_y");
                Integer maxY = poly2dResultSet.getInt("max_y");
                List<BlockVector2D> points = new ArrayList<BlockVector2D>();

                poly2dVectorStatement.setString(1, id);
                ResultSet poly2dVectorResultSet = poly2dVectorStatement.executeQuery();

                while(poly2dVectorResultSet.next()) {
                    points.add(new BlockVector2D(
                            poly2dVectorResultSet.getInt("x"),
                            poly2dVectorResultSet.getInt("z")
                    ));
                }

                if (points.size() < 3) {
                    logger.warning(String.format("Invalid polygonal region '%s': region only has %d point(s). Ignoring.", id, points.size()));
                    continue;
                }
                
                closeResource(poly2dVectorResultSet);

                ProtectedRegion region = new ProtectedPolygonalRegion(id, points, minY, maxY);

                region.setPriority(poly2dResultSet.getInt("priority"));

                this.loadFlags(region);
                this.loadOwnersAndMembers(region);

                regions.put(poly2dResultSet.getString("id"), region);

                String parentId = poly2dResultSet.getString("parent");
                if (parentId != null) {
                    parentSets.put(region, parentId);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            logger.warning("Unable to load regions from sql database: " + ex.getMessage());
            Throwable t = ex.getCause();
            while (t != null) {
                logger.warning("\t\tCause: " + t.getMessage());
                t = t.getCause();
            }
        } finally {
            closeResource(poly2dResultSet);
            closeResource(poly2dRegionStatement);
            closeResource(poly2dVectorStatement);
        }

        poly2dRegions = regions;
    }

    @Override
    public void load() throws ProtectionDatabaseException {
        try {
            connect();
        } catch (SQLException ex) {
            throw new ProtectionDatabaseException(ex);
        }

        parentSets = new HashMap<ProtectedRegion,String>();

        // We load the cuboid regions first, as this is likely to be the
        // largest dataset. This should save time in regards to the putAll()s
        this.loadCuboid();
        Map<String,ProtectedRegion> regions = this.cuboidRegions;
        this.cuboidRegions = null;

        this.loadPoly2d();
        regions.putAll(this.poly2dRegions);
        this.poly2dRegions = null;

        this.loadGlobal();
        regions.putAll(this.globalRegions);
        this.globalRegions = null;

        // Relink parents // Taken verbatim from YAMLDatabase
        for (Map.Entry<ProtectedRegion, String> entry : parentSets.entrySet()) {
            ProtectedRegion parent = regions.get(entry.getValue());
            if (parent != null) {
                try {
                    entry.getKey().setParent(parent);
                } catch (CircularInheritanceException e) {
                    logger.warning("Circular inheritance detect with '"
                            + entry.getValue() + "' detected as a parent");
                }
            } else {
                logger.warning("Unknown region parent: " + entry.getValue());
            }
        }

        this.regions = regions;
    }


    /*
     * Returns the database id for the user
     * If it doesn't exits it adds the user and returns the id.
     */
    private Map<String,Integer> getUserIds(String... usernames) {
        Map<String,Integer> users = new HashMap<String,Integer>();

        if (usernames.length < 1) return users;

        ResultSet findUsersResults = null;
        PreparedStatement insertUserStatement = null;
        PreparedStatement findUsersStatement = null;
        try {
            findUsersStatement = this.conn.prepareStatement(
                    String.format(
                            "SELECT " +
                            "`user`.`id`, " +
                            "`user`.`name` " +
                            "FROM `" + config.sqlTablePrefix + "user` AS `user` " +
                            "WHERE `name` IN (%s)",
                            RegionDBUtil.preparePlaceHolders(usernames.length)
                    )
            );

            RegionDBUtil.setValues(findUsersStatement, usernames);

            findUsersResults = findUsersStatement.executeQuery();

            while(findUsersResults.next()) {
                users.put(findUsersResults.getString("name"), findUsersResults.getInt("id"));
            }

            insertUserStatement = this.conn.prepareStatement(
                    "INSERT INTO " +
                    "`" + config.sqlTablePrefix + "user` ( " +
                    "`id`, " +
                    "`name`" +
                    ") VALUES (null, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );

            for (String username : usernames) {
                if (!users.containsKey(username)) {
                    insertUserStatement.setString(1, username);
                    insertUserStatement.execute();
                    ResultSet generatedKeys = insertUserStatement.getGeneratedKeys();
                    if (generatedKeys.first()) {
                        users.put(username, generatedKeys.getInt(1));
                    } else {
                        logger.warning("Could not get the database id for user " + username);
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            logger.warning("Could not get the database id for the users " + usernames.toString() + "\n\t" + ex.getMessage());
            Throwable t = ex.getCause();
            while (t != null) {
                logger.warning(t.getMessage());
                t = t.getCause();
            }
        } finally {
            closeResource(findUsersResults);
            closeResource(findUsersStatement);
            closeResource(insertUserStatement);
        }

        return users;
    }


    /*
     * Returns the database id for the groups
     * If it doesn't exits it adds the group and returns the id.
     */
    private Map<String,Integer> getGroupIds(String... groupnames) {
        Map<String,Integer> groups = new HashMap<String,Integer>();

        if (groupnames.length < 1) return groups;

        PreparedStatement findGroupsStatement = null;
        ResultSet findGroupsResults = null;
        PreparedStatement insertGroupStatement = null;
        try {
            findGroupsStatement = this.conn.prepareStatement(
                    String.format(
                            "SELECT " +
                            "`group`.`id`, " +
                            "`group`.`name` " +
                            "FROM `" + config.sqlTablePrefix + "group` AS `group` " +
                            "WHERE `name` IN (%s)",
                            RegionDBUtil.preparePlaceHolders(groupnames.length)
                    )
            );

            RegionDBUtil.setValues(findGroupsStatement, groupnames);

            findGroupsResults = findGroupsStatement.executeQuery();

            while(findGroupsResults.next()) {
                groups.put(findGroupsResults.getString("name"), findGroupsResults.getInt("id"));
            }

            insertGroupStatement = this.conn.prepareStatement(
                    "INSERT INTO " +
                    "`" + config.sqlTablePrefix + "group` ( " +
                    "`id`, " +
                    "`name`" +
                    ") VALUES (null, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );

            for (String groupname : groupnames) {
                if (!groups.containsKey(groupname)) {
                    insertGroupStatement.setString(1, groupname);
                    insertGroupStatement.execute();
                    ResultSet generatedKeys = insertGroupStatement.getGeneratedKeys();
                    if (generatedKeys.first()) {
                        groups.put(groupname, generatedKeys.getInt(1));
                    } else {
                        logger.warning("Could not get the database id for user " + groupname);
                    }
                }
            }
        } catch (SQLException ex) {
            logger.warning("Could not get the database id for the groups " + groupnames.toString() + ex.getMessage());
        } finally {
            closeResource(findGroupsResults);
            closeResource(findGroupsStatement);
            closeResource(insertGroupStatement);
        }

        return groups;
    }

    /*
     * As we don't get notified on the creation/removal of regions:
     *  1) We get a list of all of the in-database regions
     *  2) We iterate over all of the in-memory regions
     *  2a) If the region is in the database, we update the database and
     *      remove the region from the in-database list
     *   b) If the region is not in the database, we insert it
     *  3) We iterate over what remains of the in-database list and remove
     *     them from the database
     *
     * TODO: Look at adding/removing/updating the database when the in
     *       memory region is created/remove/updated
     *
     * @see com.sk89q.worldguard.protection.databases.ProtectionDatabase#save()
     */
    @Override
    public void save() throws ProtectionDatabaseException {
        try {
            connect();
        } catch (SQLException ex) {
            throw new ProtectionDatabaseException(ex);
        }

        List<String> regionsInDatabase = new ArrayList<String>();

        PreparedStatement getAllRegionsStatement = null;
        ResultSet getAllRegionsResult = null;
        try {
            getAllRegionsStatement = this.conn.prepareStatement(
                    "SELECT `region`.`id` FROM " +
                    "`" + config.sqlTablePrefix + "region` AS `region` " +
                    "WHERE `world_id` = ? "
            );

            getAllRegionsStatement.setInt(1, this.worldDbId);
            getAllRegionsResult = getAllRegionsStatement.executeQuery();

            while(getAllRegionsResult.next()) {
                regionsInDatabase.add(getAllRegionsResult.getString("id"));
            }
        } catch (SQLException ex) {
            logger.warning("Could not get region list for save comparison: " + ex.getMessage());
        } finally {
            closeResource(getAllRegionsResult);
            closeResource(getAllRegionsStatement);
        }

        for (Map.Entry<String, ProtectedRegion> entry : regions.entrySet()) {
            String name = entry.getKey();
            ProtectedRegion region = entry.getValue();

            try {
                if (regionsInDatabase.contains(name)) {
                    regionsInDatabase.remove(name);

                    if (region instanceof ProtectedCuboidRegion) {
                        updateRegionCuboid( (ProtectedCuboidRegion) region );
                    } else if (region instanceof ProtectedPolygonalRegion) {
                        updateRegionPoly2D( (ProtectedPolygonalRegion) region );
                    } else if (region instanceof GlobalProtectedRegion) {
                        updateRegionGlobal( (GlobalProtectedRegion) region );
                    } else {
                        this.updateRegion(region, region.getClass().getCanonicalName());
                    }
                } else {
                    if (region instanceof ProtectedCuboidRegion) {
                        insertRegionCuboid( (ProtectedCuboidRegion) region );
                    } else if (region instanceof ProtectedPolygonalRegion) {
                        insertRegionPoly2D( (ProtectedPolygonalRegion) region );
                    } else if (region instanceof GlobalProtectedRegion) {
                        insertRegionGlobal( (GlobalProtectedRegion) region );
                    } else {
                        this.insertRegion(region, region.getClass().getCanonicalName());
                    }
                }
            } catch (SQLException ex) {
                logger.warning("Could not save region " + region.getId().toLowerCase() + ": " + ex.getMessage());
                throw new ProtectionDatabaseException(ex);
            }
        }

        for (Map.Entry<String, ProtectedRegion> entry : regions.entrySet()) {
            PreparedStatement setParentStatement = null;
            try {
                if (entry.getValue().getParent() == null) continue;

                setParentStatement = this.conn.prepareStatement(
                        "UPDATE `" + config.sqlTablePrefix + "region` SET " +
                        "`parent` = ? " +
                        "WHERE `id` = ? AND `world_id` = " + this.worldDbId
                );

                setParentStatement.setString(1, entry.getValue().getParent().getId().toLowerCase());
                setParentStatement.setString(2, entry.getValue().getId().toLowerCase());

                setParentStatement.execute();
            } catch (SQLException ex) {
                logger.warning("Could not save region parents " + entry.getValue().getId().toLowerCase() + ": " + ex.getMessage());
                throw new ProtectionDatabaseException(ex);
            } finally {
                closeResource(setParentStatement);
            }
        }

        for (String name : regionsInDatabase) {
            PreparedStatement removeRegion = null;
            try {
                removeRegion = this.conn.prepareStatement(
                        "DELETE FROM `" + config.sqlTablePrefix + "region` WHERE `id` = ? "
                );

                removeRegion.setString(1, name);
                removeRegion.execute();
            } catch (SQLException ex) {
                logger.warning("Could not remove region from database " + name + ": " + ex.getMessage());
            } finally {
                closeResource(removeRegion);
            }
        }

    }

    private void updateFlags(ProtectedRegion region) throws SQLException {
        PreparedStatement clearCurrentFlagStatement = null;
        try {
            clearCurrentFlagStatement = this.conn.prepareStatement(
                    "DELETE FROM `" + config.sqlTablePrefix + "region_flag` " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId
            );

            clearCurrentFlagStatement.setString(1, region.getId().toLowerCase());
            clearCurrentFlagStatement.execute();

            for (Map.Entry<Flag<?>, Object> entry : region.getFlags().entrySet()) {
                if (entry.getValue() == null) continue;

                Object flag = sqlMarshal(marshalFlag(entry.getKey(), entry.getValue()));

                PreparedStatement insertFlagStatement = null;
                try {
                    insertFlagStatement = this.conn.prepareStatement(
                            "INSERT INTO `" + config.sqlTablePrefix + "region_flag` ( " +
                            "`id`, " +
                            "`region_id`, " +
                            "`world_id`, " +
                            "`flag`, " +
                            "`value` " +
                            ") VALUES (null, ?, " + this.worldDbId + ", ?, ?)"
                    );

                    insertFlagStatement.setString(1, region.getId().toLowerCase());
                    insertFlagStatement.setString(2, entry.getKey().getName());
                    insertFlagStatement.setObject(3, flag);

                    insertFlagStatement.execute();
                } finally {
                    closeResource(insertFlagStatement);
                }
            }
        } finally {
            closeResource(clearCurrentFlagStatement);
        }
    }

    private void updatePlayerAndGroups(ProtectedRegion region, Boolean owners) throws SQLException {
        DefaultDomain domain;

        if (owners) {
            domain = region.getOwners();
        } else {
            domain = region.getMembers();
        }

        PreparedStatement deleteUsersForRegion = null;
        PreparedStatement insertUsersForRegion = null;
        PreparedStatement deleteGroupsForRegion = null;
        PreparedStatement insertGroupsForRegion = null;

        try {
            deleteUsersForRegion = this.conn.prepareStatement(
                    "DELETE FROM `" + config.sqlTablePrefix + "region_players` " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId + " " +
                    "AND `owner` = ?"
            );

            deleteUsersForRegion.setString(1, region.getId().toLowerCase());
            deleteUsersForRegion.setBoolean(2, owners);
            deleteUsersForRegion.execute();

            insertUsersForRegion = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region_players` " +
                    "(`region_id`, `world_id`, `user_id`, `owner`) " +
                    "VALUES (?, " + this.worldDbId + ",  ?, ?)"
            );

            Set<String> var = domain.getPlayers();

            for (Integer player : getUserIds(var.toArray(new String[var.size()])).values()) {
                insertUsersForRegion.setString(1, region.getId().toLowerCase());
                insertUsersForRegion.setInt(2, player);
                insertUsersForRegion.setBoolean(3, owners);

                insertUsersForRegion.execute();
            }

            deleteGroupsForRegion = this.conn.prepareStatement(
                    "DELETE FROM `" + config.sqlTablePrefix + "region_groups` " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId + " " +
                    "AND `owner` = ?"
            );

            deleteGroupsForRegion.setString(1, region.getId().toLowerCase());
            deleteGroupsForRegion.setBoolean(2, owners);
            deleteGroupsForRegion.execute();

            insertGroupsForRegion = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region_groups` " +
                    "(`region_id`, `world_id`, `group_id`, `owner`) " +
                    "VALUES (?, " + this.worldDbId + ",  ?, ?)"
            );

            Set<String> groupVar = domain.getGroups();
            for (Integer group : getGroupIds(groupVar.toArray(new String[groupVar.size()])).values()) {
                insertGroupsForRegion.setString(1, region.getId().toLowerCase());
                insertGroupsForRegion.setInt(2, group);
                insertGroupsForRegion.setBoolean(3, owners);

                insertGroupsForRegion.execute();
            }
        } finally {
            closeResource(deleteGroupsForRegion);
            closeResource(deleteUsersForRegion);
            closeResource(insertGroupsForRegion);
            closeResource(insertUsersForRegion);
        }
    }

    @SuppressWarnings("unchecked")
    private <V> Object marshalFlag(Flag<V> flag, Object val) {
        return flag.marshal( (V) val );
    }

    private void insertRegion(ProtectedRegion region, String type) throws SQLException {
        PreparedStatement insertRegionStatement = null;
        try {
            insertRegionStatement = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region` (" +
                    "`id`, " +
                    "`world_id`, " +
                    "`type`, " +
                    "`priority`, " +
                    "`parent` " +
                    ") VALUES (?, ?, ?, ?, null)"
            );

            insertRegionStatement.setString(1, region.getId().toLowerCase());
            insertRegionStatement.setInt(2, this.worldDbId);
            insertRegionStatement.setString(3, type);
            insertRegionStatement.setInt(4, region.getPriority());

            insertRegionStatement.execute();
        } finally {
            closeResource(insertRegionStatement);
        }

        updateFlags(region);

        updatePlayerAndGroups(region, false);
        updatePlayerAndGroups(region, true);
    }

    private void insertRegionCuboid(ProtectedCuboidRegion region) throws SQLException {
        insertRegion(region, "cuboid");

        PreparedStatement insertCuboidRegionStatement = null;
        try {
            insertCuboidRegionStatement = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region_cuboid` (" +
                    "`region_id`, " +
                    "`world_id`, " +
                    "`min_z`, " +
                    "`min_y`, " +
                    "`min_x`, " +
                    "`max_z`, " +
                    "`max_y`, " +
                    "`max_x` " +
                    ") VALUES (?, " + this.worldDbId + ", ?, ?, ?, ?, ?, ?)"
            );

            BlockVector min = region.getMinimumPoint();
            BlockVector max = region.getMaximumPoint();

            insertCuboidRegionStatement.setString(1, region.getId().toLowerCase());
            insertCuboidRegionStatement.setInt(2, min.getBlockZ());
            insertCuboidRegionStatement.setInt(3, min.getBlockY());
            insertCuboidRegionStatement.setInt(4, min.getBlockX());
            insertCuboidRegionStatement.setInt(5, max.getBlockZ());
            insertCuboidRegionStatement.setInt(6, max.getBlockY());
            insertCuboidRegionStatement.setInt(7, max.getBlockX());

            insertCuboidRegionStatement.execute();
        } finally {
            closeResource(insertCuboidRegionStatement);
        }
    }

    private void insertRegionPoly2D(ProtectedPolygonalRegion region) throws SQLException {
        insertRegion(region, "poly2d");

        PreparedStatement insertPoly2dRegionStatement = null;
        try {
            insertPoly2dRegionStatement = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region_poly2d` (" +
                    "`region_id`, " +
                    "`world_id`, " +
                    "`max_y`, " +
                    "`min_y` " +
                    ") VALUES (?, " + this.worldDbId + ", ?, ?)"
            );

            insertPoly2dRegionStatement.setString(1, region.getId().toLowerCase());
            insertPoly2dRegionStatement.setInt(2, region.getMaximumPoint().getBlockY());
            insertPoly2dRegionStatement.setInt(3, region.getMinimumPoint().getBlockY());

            insertPoly2dRegionStatement.execute();
        } finally {
            closeResource(insertPoly2dRegionStatement);
        }

        updatePoly2dPoints(region);
    }

    private void updatePoly2dPoints(ProtectedPolygonalRegion region) throws SQLException {
        PreparedStatement clearPoly2dPointsForRegionStatement = null;
        PreparedStatement insertPoly2dPointStatement = null;

        try {
            clearPoly2dPointsForRegionStatement = this.conn.prepareStatement(
                    "DELETE FROM `" + config.sqlTablePrefix + "region_poly2d_point` " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId
            );

            clearPoly2dPointsForRegionStatement.setString(1, region.getId().toLowerCase());

            clearPoly2dPointsForRegionStatement.execute();

            insertPoly2dPointStatement = this.conn.prepareStatement(
                    "INSERT INTO `" + config.sqlTablePrefix + "region_poly2d_point` (" +
                    "`id`, " +
                    "`region_id`, " +
                    "`world_id`, " +
                    "`z`, " +
                    "`x` " +
                    ") VALUES (null, ?, " + this.worldDbId + ", ?, ?)"
            );

            String lowerId = region.getId().toLowerCase();
            for (BlockVector2D point : region.getPoints()) {
                insertPoly2dPointStatement.setString(1, lowerId);
                insertPoly2dPointStatement.setInt(2, point.getBlockZ());
                insertPoly2dPointStatement.setInt(3, point.getBlockX());

                insertPoly2dPointStatement.execute();
            }
        } finally {
            closeResource(clearPoly2dPointsForRegionStatement);
            closeResource(insertPoly2dPointStatement);
        }
    }

    private void insertRegionGlobal(GlobalProtectedRegion region) throws SQLException {
        insertRegion(region, "global");
    }

    private void updateRegion(ProtectedRegion region, String type) throws SQLException  {
        PreparedStatement updateRegionStatement = null;
        try {
            updateRegionStatement = this.conn.prepareStatement(
                    "UPDATE `" + config.sqlTablePrefix + "region` SET " +
                    "`priority` = ? WHERE `id` = ? AND `world_id` = " + this.worldDbId
            );

            updateRegionStatement.setInt(1, region.getPriority());
            updateRegionStatement.setString(2, region.getId().toLowerCase());

            updateRegionStatement.execute();
        } finally {
            closeResource(updateRegionStatement);
        }

        updateFlags(region);

        updatePlayerAndGroups(region, false);
        updatePlayerAndGroups(region, true);
    }

    private void updateRegionCuboid(ProtectedCuboidRegion region) throws SQLException  {
        updateRegion(region, "cuboid");

        PreparedStatement updateCuboidRegionStatement = null;
        try {
            updateCuboidRegionStatement = this.conn.prepareStatement(
                    "UPDATE `" + config.sqlTablePrefix + "region_cuboid` SET " +
                    "`min_z` = ?, " +
                    "`min_y` = ?, " +
                    "`min_x` = ?, " +
                    "`max_z` = ?, " +
                    "`max_y` = ?, " +
                    "`max_x` = ? " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId
            );

            BlockVector min = region.getMinimumPoint();
            BlockVector max = region.getMaximumPoint();

            updateCuboidRegionStatement.setInt(1, min.getBlockZ());
            updateCuboidRegionStatement.setInt(2, min.getBlockY());
            updateCuboidRegionStatement.setInt(3, min.getBlockX());
            updateCuboidRegionStatement.setInt(4, max.getBlockZ());
            updateCuboidRegionStatement.setInt(5, max.getBlockY());
            updateCuboidRegionStatement.setInt(6, max.getBlockX());
            updateCuboidRegionStatement.setString(7, region.getId().toLowerCase());

            updateCuboidRegionStatement.execute();
        } finally {
            closeResource(updateCuboidRegionStatement);
        }
    }

    private void updateRegionPoly2D(ProtectedPolygonalRegion region) throws SQLException  {
        updateRegion(region, "poly2d");

        PreparedStatement updatePoly2dRegionStatement = null;
        try {
            updatePoly2dRegionStatement = this.conn.prepareStatement(
                    "UPDATE `" + config.sqlTablePrefix + "region_poly2d` SET " +
                    "`max_y` = ?, " +
                    "`min_y` = ? " +
                    "WHERE `region_id` = ? " +
                    "AND `world_id` = " + this.worldDbId
            );

            updatePoly2dRegionStatement.setInt(1, region.getMaximumPoint().getBlockY());
            updatePoly2dRegionStatement.setInt(2, region.getMinimumPoint().getBlockY());
            updatePoly2dRegionStatement.setString(3, region.getId().toLowerCase());

            updatePoly2dRegionStatement.execute();
        } finally {
            closeResource(updatePoly2dRegionStatement);
        }
        updatePoly2dPoints(region);
    }

    private void updateRegionGlobal(GlobalProtectedRegion region) throws SQLException {
        updateRegion(region, "global");
    }

    private void closeResource(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {}
        }
    }

    private void closeResource(Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException e) {}
        }
    }

    @Override
    public Map<String, ProtectedRegion> getRegions() {
        return regions;
    }

    @Override
    public void setRegions(Map<String, ProtectedRegion> regions) {
        this.regions = regions;
    }

    protected Object sqlUnmarshal(String rawValue) {
        try {
            return yaml.load(rawValue);
        } catch (YAMLException e) {
            return String.valueOf(rawValue);
        }
    }

    protected String sqlMarshal(Object rawObject) {
        return yaml.dump(rawObject);
    }
}
