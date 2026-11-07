package io.github.novablaze.novaguard.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
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
    /** How much narrower than the player the ground box is, so touching walls are not ground. */
    private static final double INSET = 0.001;

    private Environment() {
    }

    public static Probe probe(Player player, Location at) {
        World world = at.getWorld();
        double half = player.getWidth() / 2.0;
        double height = player.getHeight();
        double x = at.getX();
        double y = at.getY();
        double z = at.getZ();

        // Ground right under the feet. The box is a hair narrower than the player, so a wall the player is
        // touching never counts as ground (otherwise fly/spider cheats are safe while hugging a wall).
        BoundingBox feet = new BoundingBox(x - half + INSET, y - 0.12, z - half + INSET,
            x + half - INSET, y + 0.02, z + half - INSET);
        boolean grounded = world.hasCollisionsIn(feet) || edgeGround(world, x, y, z, half);

        boolean supported = false;
        int minX = floor(x - half - 0.1);
        int maxX = floor(x + half + 0.1);
        int minZ = floor(z - half - 0.1);
        int maxZ = floor(z + half + 0.1);
        for (int bx = minX; bx <= maxX && !supported; bx++) {
            for (int bz = minZ; bz <= maxZ && !supported; bz++) {
                for (int by = floor(y - 0.2); by <= floor(y + height); by++) {
                    if (isSupporting(world.getBlockAt(bx, by, bz), y, height)) {
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

    /**
     * Something in this block holds the player up or slows them down. Water and lava only count when the
     * player's body is really below the fluid's surface: standing <i>on top of</i> water is not swimming,
     * it is the Jesus cheat.
     */
    private static boolean isSupporting(Block block, double feetY, double height) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        double top = fluidTop(block);
        if (!Double.isNaN(top)) {
            return feetY < top && feetY + height > block.getY();
        }
        if (Tag.CLIMBABLE.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case COBWEB, POWDER_SNOW, SCAFFOLDING, HONEY_BLOCK, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    /**
     * The height of the fluid surface in this block (world Y), or NaN when the block holds no fluid. A source
     * or flowing block is 8/9 full minus 1/9 per flow level; falling fluid, or fluid with the same fluid
     * above it, fills the whole block.
     */
    static double fluidTop(Block block) {
        Material type = block.getType();
        boolean water = type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.KELP
            || type == Material.KELP_PLANT || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
            || block.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
        boolean lava = type == Material.LAVA;
        if (!water && !lava) {
            return Double.NaN;
        }
        Block above = block.getRelative(0, 1, 0);
        boolean sameAbove = lava ? above.getType() == Material.LAVA : !Double.isNaN(waterOnly(above));
        if (sameAbove || type == Material.BUBBLE_COLUMN) {
            return block.getY() + 1.0;
        }
        int level = block.getBlockData() instanceof Levelled levelled ? levelled.getLevel() : 0;
        if (level >= 8) {
            return block.getY() + 1.0;
        }
        return block.getY() + (8 - level) / 9.0;
    }

    private static double waterOnly(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN || type == Material.KELP
            || type == Material.KELP_PLANT || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
            || block.getBlockData() instanceof Waterlogged w && w.isWaterlogged() ? 0 : Double.NaN;
    }

    /**
     * Standing on the very edge of a block (the feet overlap it by less than {@link #INSET}). Only surfaces
     * whose top is at foot level count, so the side of a wall never does.
     */
    private static boolean edgeGround(World world, double x, double y, double z, double half) {
        BoundingBox wide = new BoundingBox(x - half - EDGE, y - 0.12, z - half - EDGE,
            x + half + EDGE, y + 0.02, z + half + EDGE);
        for (int bx = floor(wide.getMinX()); bx <= floor(wide.getMaxX()); bx++) {
            for (int bz = floor(wide.getMinZ()); bz <= floor(wide.getMaxZ()); bz++) {
                for (int by = floor(wide.getMinY()); by <= floor(wide.getMaxY()); by++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.isPassable()) {
                        continue;
                    }
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        BoundingBox shifted = part.clone().shift(bx, by, bz);
                        double topY = shifted.getMaxY();
                        if (topY >= y - 0.12 && topY <= y + 0.02 && shifted.overlaps(wide)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSoftLanding(Material type) {
        return type == Material.SLIME_BLOCK || type == Material.HAY_BLOCK || type == Material.HONEY_BLOCK
            || type == Material.POWDER_SNOW || type == Material.COBWEB || Tag.BEDS.isTagged(type);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
