package io.github.novablaze.novaguard.math;

/** An axis-aligned box (like a hitbox). Plain numbers only, so it can be unit tested without a server. */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** Box of an entity standing with its feet at (x, y, z). */
    public static Box ofEntity(double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        return new Box(x - half, y, z - half, x + half, y + height, z + half);
    }

    public Box expand(double amount) {
        return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
    }

    public double centerX() {
        return (minX + maxX) / 2.0;
    }

    public double centerY() {
        return (minY + maxY) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0;
    }
}
