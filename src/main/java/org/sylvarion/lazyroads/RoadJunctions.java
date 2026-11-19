package org.sylvarion.lazyroads;

import org.sylvarion.lazyroads.RoadSmoother.Surface;

final class RoadJunctions {
    private final Surface[] surfaces;
    private final int length;

    RoadJunctions(Surface[] surfaces, int length) {
        this.surfaces = surfaces;
        this.length = length;
    }

    boolean[] find() {
        int[] west = ends(-length, 0);
        int[] east = ends(length, 0);
        int[] north = ends(-1, -1);
        int[] south = ends(1, 1);
        boolean[] junction = new boolean[surfaces.length];
        for (int cell = 0; cell < surfaces.length; cell++) {
            if (!isRoad(cell)) {
                continue;
            }
            int width = (east[cell] - west[cell]) / length + 1;
            int depth = south[cell] - north[cell] + 1;
            int approaches = 0;
            if (extendsBeyondJunction((cell - west[cell]) / length, depth, south[west[cell]] - north[west[cell]] + 1)) {
                approaches++;
            }
            if (extendsBeyondJunction((east[cell] - cell) / length, depth, south[east[cell]] - north[east[cell]] + 1)) {
                approaches++;
            }
            if (extendsBeyondJunction(cell - north[cell], width, (east[north[cell]] - west[north[cell]]) / length + 1)) {
                approaches++;
            }
            if (extendsBeyondJunction(south[cell] - cell, width, (east[south[cell]] - west[south[cell]]) / length + 1)) {
                approaches++;
            }
            junction[cell] = approaches >= 3;
        }
        return junction;
    }

    private int[] ends(int step, int zStep) {
        int[] ends = new int[surfaces.length];
        int start = step < 0 ? 0 : surfaces.length - 1;
        int order = step < 0 ? 1 : -1;
        for (int cell = start; cell >= 0 && cell < surfaces.length; cell += order) {
            ends[cell] = cell;
            int adjacent = cell + step;
            int z = cell % length + zStep;
            if (z >= 0 && z < length && adjacent >= 0 && adjacent < surfaces.length
                    && isRoad(cell) && isRoad(adjacent)
                    && Math.abs(surfaces[cell].height() - surfaces[adjacent].height()) <= 2) {
                ends[cell] = ends[adjacent];
            }
        }
        return ends;
    }

    private boolean isRoad(int cell) {
        return surfaces[cell] != null && "roadway".equals(surfaces[cell].group());
    }

    private boolean extendsBeyondJunction(int distance, int span, int approachWidth) {
        // A narrow approach must extend beyond the shared pavement. A bend typically has two such approaches.
        return distance > approachWidth / 2 && span >= 2 * approachWidth;
    }
}
