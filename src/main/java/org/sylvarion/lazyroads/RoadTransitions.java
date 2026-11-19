package org.sylvarion.lazyroads;

import java.util.Arrays;

import org.sylvarion.lazyroads.RoadSmoother.Surface;

final class RoadTransitions {
    private static final int[] DX = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DZ = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int NO_BAND = Integer.MIN_VALUE;

    private final Surface[] surfaces;
    private final int length;
    private final int[] bands;
    private final int[] queue;
    private final int[] surfaceOffsets;

    RoadTransitions(Surface[] surfaces, int length) {
        this.surfaces = surfaces;
        this.length = length;
        bands = new int[surfaces.length];
        queue = new int[surfaces.length];
        surfaceOffsets = new int[surfaces.length];
    }

    int[] find() {
        assignBands();
        int[] heights = new int[surfaces.length];
        for (int cell = 0; cell < surfaces.length; cell++) {
            if (surfaces[cell] != null) {
                heights[cell] = surfaces[cell].height();
                String group = surfaces[cell].group();
                surfaceOffsets[cell] = "curb".equals(group) || "sidewalk".equals(group) ? 1 : 0;
            }
        }
        boolean[] junctions = new RoadJunctions(surfaces, length).find();
        int[] uphill = distances(2, junctions);
        int[] downhill = distances(-2, junctions);
        boolean[] visited = new boolean[surfaces.length];

        for (int index = 0; index < surfaces.length; index++) {
            if (uphill[index] == 0 || visited[index]) {
                continue;
            }

            int size = collectBand(index, visited);
            int farthestUphillDistance = 0;
            boolean restoreHalfSteps = false;
            boolean sharedTerrace = false;
            boolean meetsJunction = false;
            for (int i = 0; i < size; i++) {
                int cell = queue[i];
                farthestUphillDistance = Math.max(farthestUphillDistance, uphill[cell]);
                sharedTerrace |= !sameGroup(index, cell);
                restoreHalfSteps |= touchesCurb(cell);
                meetsJunction |= junctions[cell];
            }
            TransitionLine line = sharedTerrace && !meetsJunction ? fitTransitionLine(size) : null;
            // At the end of a road or selection, use the far end of this level as its downhill edge.
            for (int i = 0; i < size; i++) {
                int cell = queue[i];
                int distanceFromLowEdge = downhill[cell] != 0
                        ? downhill[cell]
                        : farthestUphillDistance + 1 - uphill[cell];
                if (heights[cell] == bands[cell] || sharedTerrace || restoreHalfSteps) {
                    boolean raise = line == null ? uphill[cell] <= distanceFromLowEdge
                            : uphill[cell] == 1 || line.includes(cell / length, cell % length);
                    heights[cell] = bands[cell] + (raise ? 1 : 0);
                }
            }
        }
        return heights;
    }

    int[] baseHeights() {
        return bands;
    }

    private TransitionLine fitTransitionLine(int size) {
        Edge uphill = new Edge();
        Edge downhill = new Edge();
        for (int i = 0; i < size; i++) {
            int cell = queue[i];
            int x = cell / length;
            int z = cell % length;
            for (int direction = 0; direction < DX.length; direction++) {
                int dx = DX[direction];
                int dz = DZ[direction];
                if (dx != 0 && dz != 0) {
                    continue;
                }
                int adjacent = neighbor(cell, direction);
                if (sameGroup(cell, adjacent)) {
                    if (bands[adjacent] == bands[cell] + 2) {
                        uphill.add(x, z, dx, dz);
                    } else if (bands[adjacent] == bands[cell] - 2) {
                        downhill.add(x, z, dx, dz);
                    }
                }
            }
        }
        long xDirection = (long) uphill.xDirection - downhill.xDirection;
        long zDirection = (long) uphill.zDirection - downhill.zDirection;
        long faces = (long) uphill.count + downhill.count;
        if (uphill.count == 0 || 4 * (Math.abs(xDirection) + Math.abs(zDirection)) < 3 * faces) {
            // Opposing slopes in one terrace need local distances rather than one common direction.
            return null;
        }
        double upper = uphill.project(xDirection, zDirection);
        double lower;
        if (downhill.count > 0) {
            lower = downhill.project(xDirection, zDirection);
        } else {
            long first = Long.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                int cell = queue[i];
                first = Math.min(first, xDirection * (cell / length) + zDirection * (cell % length));
            }
            lower = first - 0.5 * Math.max(Math.abs(xDirection), Math.abs(zDirection));
        }
        return upper > lower ? new TransitionLine(xDirection, zDirection, (upper + lower) / 2) : null;
    }

    private boolean touchesCurb(int cell) {
        if (!"sidewalk".equals(surfaces[cell].group())) {
            return false;
        }
        for (int direction = 0; direction < DX.length; direction++) {
            int neighbor = neighbor(cell, direction);
            if (neighbor >= 0 && surfaces[neighbor] != null && "curb".equals(surfaces[neighbor].group())
                    && Math.abs(surfaces[neighbor].height() - surfaces[cell].height()) <= 2) {
                return true;
            }
        }
        return false;
    }

    private void assignBands() {
        Arrays.fill(bands, NO_BAND);
        for (int index = 0; index < surfaces.length; index++) {
            if (surfaces[index] == null || bands[index] != NO_BAND) {
                continue;
            }

            int highest = surfaces[index].height();
            int size = 1;
            queue[0] = index;
            bands[index] = 0;
            for (int i = 0; i < size; i++) {
                int cell = queue[i];
                int height = surfaces[cell].height();
                highest = Math.max(highest, height);
                for (int direction = 0; direction < DX.length; direction++) {
                    int neighbor = neighbor(cell, direction);
                    if (sameGroup(cell, neighbor) && bands[neighbor] == NO_BAND
                            && Math.abs(height - surfaces[neighbor].height()) <= 3) {
                        bands[neighbor] = 0;
                        queue[size++] = neighbor;
                    }
                }
            }

            // The highest terrace stays at its original height. Pair lower half-step surfaces with their terraces.
            for (int i = 0; i < size; i++) {
                int cell = queue[i];
                int height = surfaces[cell].height();
                bands[cell] = height - Math.floorMod(height - highest, 2);
            }
        }
    }

    private int[] distances(int towardHeight, boolean[] junctions) {
        int[] distance = new int[surfaces.length];
        int size = 0;
        for (int cell = 0; cell < surfaces.length; cell++) {
            if (surfaces[cell] == null) {
                continue;
            }
            // Keep each approach's transition between its own rise and the shared pavement.
            if (towardHeight < 0 && junctions[cell]) {
                distance[cell] = 1;
                queue[size++] = cell;
                continue;
            }
            for (int direction = 0; direction < DX.length; direction++) {
                // A diagonal contact across the shared pavement does not start a ramp.
                if (junctions[cell]
                        && DX[direction] != 0 && DZ[direction] != 0) {
                    continue;
                }
                int neighbor = neighbor(cell, direction);
                if (sameGroup(cell, neighbor) && bands[neighbor] == bands[cell] + towardHeight) {
                    distance[cell] = 1;
                    queue[size++] = cell;
                    break;
                }
            }
        }

        for (int i = 0; i < size; i++) {
            int cell = queue[i];
            for (int direction = 0; direction < DX.length; direction++) {
                int neighbor = neighbor(cell, direction);
                if (sameBand(cell, neighbor) && distance[neighbor] == 0) {
                    distance[neighbor] = distance[cell] + 1;
                    queue[size++] = neighbor;
                }
            }
        }
        return distance;
    }

    private int collectBand(int start, boolean[] visited) {
        int size = 1;
        queue[0] = start;
        visited[start] = true;
        for (int i = 0; i < size; i++) {
            int cell = queue[i];
            for (int direction = 0; direction < DX.length; direction++) {
                int neighbor = neighbor(cell, direction);
                if (sameBand(cell, neighbor) && !visited[neighbor]) {
                    visited[neighbor] = true;
                    queue[size++] = neighbor;
                }
            }
        }
        return size;
    }

    private boolean sameBand(int cell, int neighbor) {
        if (neighbor < 0 || surfaces[neighbor] == null
                || bands[cell] - surfaceOffsets[cell] != bands[neighbor] - surfaceOffsets[neighbor]) {
            return false;
        }
        String group = surfaces[cell].group();
        String adjacent = surfaces[neighbor].group();
        return group.equals(adjacent) || ("curb".equals(group) && isRoadOrPath(adjacent))
                || ("curb".equals(adjacent) && isRoadOrPath(group));
    }

    private boolean isRoadOrPath(String group) {
        return "roadway".equals(group) || "sidewalk".equals(group);
    }

    private boolean sameGroup(int cell, int neighbor) {
        return neighbor >= 0 && surfaces[neighbor] != null
                && surfaces[cell].group().equals(surfaces[neighbor].group());
    }

    private int neighbor(int cell, int direction) {
        int z = cell % length + DZ[direction];
        int index = cell + DX[direction] * length + DZ[direction];
        return z >= 0 && z < length && index >= 0 && index < surfaces.length ? index : -1;
    }

    private static final class Edge {
        private long xSum;
        private long zSum;
        private int xDirection;
        private int zDirection;
        private int count;

        void add(int x, int z, int dx, int dz) {
            xSum += 2L * x + dx;
            zSum += 2L * z + dz;
            xDirection += dx;
            zDirection += dz;
            count++;
        }

        double project(long dx, long dz) {
            return (xSum * (double) dx + zSum * (double) dz) / (2.0 * count);
        }
    }

    private record TransitionLine(long dx, long dz, double midpoint) {
        boolean includes(int x, int z) {
            return dx * x + dz * z >= midpoint;
        }
    }
}
