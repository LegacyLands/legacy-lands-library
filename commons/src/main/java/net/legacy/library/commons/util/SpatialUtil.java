package net.legacy.library.commons.util;

import lombok.experimental.UtilityClass;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Utility class for spatial calculations involving Bukkit Locations.
 *
 * @author qwq-dev
 * @since 2025-04-27 22:57
 */
@UtilityClass
public class SpatialUtil {
    /**
     * Checks if a target location is within the cuboid (rectangular prism) defined by two diagonal corner locations.
     *
     * <p>The time complexity of this method is O(1).
     *
     * @param loc1   the first corner of the cuboid
     * @param loc2   the second corner of the cuboid
     * @param target the location to check
     * @return {@code true} if the target location is within the cuboid (inclusive of boundaries), {@code false} otherwise.
     * Also returns {@code false} if any location is null or if the locations are not in the same world
     */
    public static boolean isWithinCuboid(Location loc1, Location loc2, Location target) {
        if (loc1 == null || loc2 == null || target == null) {
            return false;
        }

        World loc1World = loc1.getWorld();

        if (loc1World == null || !loc1World.equals(loc2.getWorld()) || !loc1World.equals(target.getWorld())) {
            return false;
        }

        double x1 = loc1.getX();
        double y1 = loc1.getY();
        double z1 = loc1.getZ();
        double x2 = loc2.getX();
        double y2 = loc2.getY();
        double z2 = loc2.getZ();

        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);

        double targetX = target.getX();
        double targetY = target.getY();
        double targetZ = target.getZ();

        return targetX >= minX && targetX <= maxX &&
                targetY >= minY && targetY <= maxY &&
                targetZ >= minZ && targetZ <= maxZ;
    }

    /**
     * Checks if there are any non-air blocks within a cuboid (rectangular prism) range around a central location.
     * Uses ChunkSnapshots for potentially faster access compared to repeated getBlockAt calls,
     * especially for larger areas spanning multiple chunks.
     *
     * <p>Warning: Checking large ranges can still be resource-intensive due to the number of blocks
     * and potential chunk loading/snapshot creation. Consider running asynchronously.
     *
     * <p>The time complexity is roughly O(xRange * yRange * zRange) in the worst case, as it needs to check
     * a volume proportional to the product of the ranges. There is no known algorithm within standard Bukkit API
     * to fundamentally reduce this complexity while guaranteeing finding any arbitrary non-air block.
     *
     * @param center the center location of the cuboid area to check
     * @param xRange the full size of the area along the X-axis
     * @param yRange the full size of the area along the Y-axis
     * @param zRange the full size of the area along the Z-axis
     *               the actual check range extends range/2 blocks in each respective direction (positive and negative)
     *               from the center block's coordinates
     * @return {@code true} if any non-air block is found within the range, {@code false} otherwise
     * Returns {@code false} if the center location or its world is null, or if any range is non-positive
     */
    public static boolean hasBlocksNearby(Location center, int xRange, int yRange, int zRange) {
        if (center == null || center.getWorld() == null) {
            return false;
        }

        if (xRange <= 0 || yRange <= 0 || zRange <= 0) {
            return false;
        }

        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        // Calculate half-ranges for extending from the center
        int halfX = xRange / 2;
        int halfY = yRange / 2;
        int halfZ = zRange / 2;

        // Calculate world coordinate boundaries
        int minX = centerX - halfX;
        int maxX = centerX + halfX;

        /*
         * Clamp minimum Y coordinate to 0. Maximum Y is NOT clamped by default due to API limitations;
         * ensure calculated maxY does not exceed world height limits to prevent errors.
         */
        int minY = Math.max(0, centerY - halfY);
        int maxY = centerY + halfY;
        int minZ = centerZ - halfZ;
        int maxZ = centerZ + halfZ;

        // Calculate chunk coordinate boundaries based on the potentially wide X/Z ranges
        int minChunkX = minX >> 4; // Equivalent to minX / 16
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // Get chunk snapshot
                ChunkSnapshot chunkSnapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(); // Using default parameters

                // Calculate the iteration bounds within this specific chunk
                int startX = Math.max(minX, chunkX << 4);
                int endX = Math.min(maxX, (chunkX << 4) + 15);
                int startZ = Math.max(minZ, chunkZ << 4);
                int endZ = Math.min(maxZ, (chunkZ << 4) + 15);

                // Iterate within the calculated bounds for this chunk
                for (int y = minY; y <= maxY; y++) {
                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            // Convert world coordinates to chunk-relative coordinates (0-15)
                            int relativeX = x & 15; // x % 16
                            int relativeZ = z & 15; // z % 16

                            // Check block type using the snapshot (Y coordinate is world Y)
                            Material blockType = chunkSnapshot.getBlockType(relativeX, y, relativeZ);

                            // Check against all air types
                            if (blockType != Material.AIR && blockType != Material.CAVE_AIR && blockType != Material.VOID_AIR) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }
}
