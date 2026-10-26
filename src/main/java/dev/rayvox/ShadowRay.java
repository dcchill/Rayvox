package dev.rayvox;

/** One bounded CPU diagnostic of the same clean snapshot and DDA rules used by GLSL. */
public final class ShadowRay {
    public record Result(boolean visible, boolean unknown, int x, int y, int z, double distance) {}

    public static Result trace(VoxelGrid grid, double x, double y, double z, double tx, double ty, double tz) {
        double[] origin = {x, y, z}, ray = {tx - x, ty - y, tz - z};
        int[] cell = {(int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)};
        int[] goal = {(int)Math.floor(tx), (int)Math.floor(ty), (int)Math.floor(tz)};
        int[] direction = new int[3];
        double[] delta = new double[3], next = new double[3];
        double length = Math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]);
        for (int axis = 0; axis < 3; axis++) {
            direction[axis] = (int)Math.signum(ray[axis]);
            delta[axis] = 1 / Math.max(Math.abs(ray[axis]), 0.0000001);
            next[axis] = direction[axis] == 0 ? 1e20 : (direction[axis] > 0
                    ? cell[axis] + 1 - origin[axis] : origin[axis] - cell[axis]) * delta[axis];
        }
        double traveled = 0;
        for (int step = 0; step < 128; step++) {
            var slot = grid.get(Math.floorDiv(cell[0], 16), Math.floorDiv(cell[1], 16), Math.floorDiv(cell[2], 16));
            if (slot == null || (slot.state() & 3) != 1) return new Result(false, true, cell[0], cell[1], cell[2], traveled * length);
            if (java.util.Arrays.equals(cell, goal)) return new Result(true, false, cell[0], cell[1], cell[2], length);
            int material = slot.data[VoxelGrid.voxelIndex(cell[0], cell[1], cell[2])] & BoxShape.MATERIAL_MASK;
            if (material == 1 || material == 2) return new Result(false, false, cell[0], cell[1], cell[2], traveled * length);
            traveled = Math.min(next[0], Math.min(next[1], next[2]));
            if (traveled >= 1) return new Result(true, false, cell[0], cell[1], cell[2], length);
            for (int axis = 0; axis < 3; axis++) if (next[axis] <= traveled + 0.0000001) {
                cell[axis] += direction[axis];
                next[axis] += delta[axis];
            }
        }
        return new Result(false, true, cell[0], cell[1], cell[2], traveled * length);
    }
}
