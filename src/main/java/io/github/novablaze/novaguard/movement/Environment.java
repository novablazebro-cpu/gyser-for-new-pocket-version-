package io.github.novablaze.novaguard.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.SulfurCube;
import org.bukkit.util.BoundingBox;

/** Looks at the blocks around a player. Every probe touches at most a few dozen blocks next to the player. */
public final class Environment {

    /**
     * @param grounded a solid block or hard entity is right under the feet (server's view)
     * @param supported something holds the player up or slows the fall: liquid, ladder, cobweb, powder snow...
     * @param onIce standing on ice
     * @param softLanding landing block that cancels or reduces fall damage
     * @param bouncy slime block or bed under the feet
     */
    public record Probe(boolean grounded, boolean supported, boolean onIce, boolean softLanding, boolean bouncy) {
    }

    private static final double EDGE = 0.03;

    private Environment() {
    }

    public static Probe probe(Player player, Location at) {
        World world = at.getWorld();
        double half = player.getWidth() / 2.0;
        double height = player.getHeight();
        double x = at.getX();
        double y = at.getY();
        double z = at.getZ();

        // Slightly wider than the player, so standing on a block edge never looks like floating.
        BoundingBox feet = new BoundingBox(x - half - EDGE, y - 0.12, z - half - EDGE,
            x + half + EDGE, y + 0.02, z + half + EDGE);
        boolean grounded = world.hasCollisionsIn(feet);

        boolean supported = false;
        int minX = floor(x - half - 0.1);
        int maxX = floor(x + half + 0.1);
        int minZ = floor(z - half - 0.1);
        int maxZ = floor(z + half + 0.1);
        for (int bx = minX; bx <= maxX && !supported; bx++) {
            for (int bz = minZ; bz <= maxZ && !supported; bz++) {
                for (int by = floor(y - 0.2); by <= floor(y + height); by++) {
                    if (isSupporting(world.getBlockAt(bx, by, bz))) {
                        supported = true;
                        break;
                    }
                }
            }
        }

        boolean onIce = false;
        boolean soft = false;
        boolean bouncy = false;
        int underY = floor(y - 0.5);
        for (int bx = floor(x - half); bx <= floor(x + half); bx++) {
            for (int bz = floor(z - half); bz <= floor(z + half); bz++) {
                Material type = world.getBlockAt(bx, underY, bz).getType();
                Material feetType = world.getBlockAt(bx, floor(y), bz).getType();
                if (Tag.ICE.isTagged(type)) {
                    onIce = true;
                }
                if (isSoftLanding(type) || isSoftLanding(feetType)) {
                    soft = true;
                }
                if (type == Material.SLIME_BLOCK || Tag.BEDS.isTagged(type) || Tag.BEDS.isTagged(feetType)) {
                    bouncy = true;
                }
            }
        }
        return new Probe(grounded, supported, onIce, soft || supported, bouncy);
    }

    /** Hard entities a player can stand on or be carried by (not always reported as collisions). */
    public static boolean platformNearby(Player player) {
        for (Entity entity : player.getNearbyEntities(1.5, 2.5, 1.5)) {
            if (entity instanceof Boat || entity instanceof Minecart || entity instanceof Shulker
                || entity instanceof HappyGhast || entity instanceof SulfurCube) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupporting(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (block.isLiquid() || Tag.CLIMBABLE.isTagged(type)) {
            return true;
        }
        switch (type) {
            case WATER, LAVA, BUBBLE_COLUMN, COBWEB, POWDER_SNOW, SCAFFOLDING, HONEY_BLOCK, SWEET_BERRY_BUSH,
                 KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> {
                return true;
            }
            default -> {
            }
        }
        BlockData data = block.getBlockData();
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private static boolean isSoftLanding(Material type) {
        return type == Material.SLIME_BLOCK || type == Material.HAY_BLOCK || type == Material.HONEY_BLOCK
            || type == Material.POWDER_SNOW || type == Material.COBWEB || Tag.BEDS.isTagged(type);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
