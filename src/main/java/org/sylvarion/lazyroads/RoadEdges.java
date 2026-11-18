package org.sylvarion.lazyroads;

import java.util.function.BiPredicate;

import org.sylvarion.lazyroads.RoadSmoother.RoadMaterial;
import org.sylvarion.lazyroads.RoadSmoother.Surface;

final class RoadEdges {
    private static final String ROAD = "roadway";
    private static final String CURB = "curb";
    private static final String PATH = "sidewalk";
    private static final int[] DX = {-1, 0, 0, 1, -1, -1, 1, 1};
    private static final int[] DZ = {0, -1, 1, 0, -1, 1, -1, 1};
    private static final int CARDINAL_COUNT = 4;

    private final Surface[] surfaces;
    private final int length;
    private final int minHeight;
    private final int maxHeight;

    RoadEdges(Surface[] surfaces, int length, int minHeight, int maxHeight) {
        this.surfaces = surfaces;
        this.length = length;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
    }

    void align(int[] heights, int[] baseHeights, RoadMaterial[] materials, BiPredicate<Integer, Integer> clearForCurb) {
        boolean preserveGrade = baseHeights == null;
        boolean[] attachedCurbs = new boolean[surfaces.length];
        for (int cell = 0; cell < surfaces.length; cell++) {
            if (!isGroup(cell, CURB)) {
                continue;
            }
            if (preserveGrade && hasMatchingRoad(cell, heights)) {
                attachedCurbs[cell] = true;
                continue;
            }
            int road = roadAcrossCurb(cell, heights);
            if (road >= 0) {
                heights[cell] = heights[road] + 1;
                attachedCurbs[cell] = true;
            }
        }

        for (int cell = 0; cell < surfaces.length; cell++) {
            if (surfaces[cell] != null && !isGroup(cell, PATH)) {
                continue;
            }
            for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
                int road = neighbor(cell, direction);
                int path = neighbor(cell, CARDINAL_COUNT - 1 - direction);
                if (!isGroup(road, ROAD) || !isGroup(path, PATH)) {
                    continue;
                }
                int height = heights[road] + 1;
                if (!canAlign(cell, height) || Math.abs(surfaces[path].height() - height) > 2) {
                    continue;
                }
                int curb = flankingCurb(cell, height, attachedCurbs, heights);
                if (curb >= 0 && (surfaces[cell] != null || clearForCurb.test(cell, height))) {
                    heights[cell] = height;
                    materials[cell] = surfaces[curb].material();
                    attachedCurbs[cell] = true;
                    break;
                }
            }
        }

        if (!preserveGrade) {
            alignPaths(heights, baseHeights, attachedCurbs);
        }
        closeSidewalkSeams(heights, attachedCurbs);
    }

    boolean hasContinuousGrade() {
        boolean hasCurb = false;
        for (int cell = 0; cell < surfaces.length; cell++) {
            Surface surface = surfaces[cell];
            if (surface == null) {
                continue;
            }
            for (int direction = 0; direction < DX.length; direction++) {
                int adjacent = neighbor(cell, direction);
                if (direction < CARDINAL_COUNT && CURB.equals(surface.group()) && isGroup(adjacent, ROAD)) {
                    hasCurb = true;
                }
                if (isGroup(adjacent, surface.group()) && Math.abs(surface.height() - surfaces[adjacent].height()) > 1
                        && (direction < CARDINAL_COUNT
                        || (!isGroup(neighbor(cell, DX[direction], 0), surface.group())
                        && !isGroup(neighbor(cell, 0, DZ[direction]), surface.group())))) {
                    return false;
                }
            }
        }
        return hasCurb;
    }

    private boolean hasMatchingRoad(int curb, int[] heights) {
        for (int direction = 0; direction < DX.length; direction++) {
            int road = neighbor(curb, direction);
            if (isGroup(road, ROAD) && heights[road] + 1 == heights[curb]) {
                return true;
            }
        }
        return false;
    }

    private int flankingCurb(int cell, int height, boolean[] attachedCurbs, int[] heights) {
        for (int first = 0; first < DX.length; first++) {
            int left = neighbor(cell, first);
            if (!isGroup(left, CURB) || !attachedCurbs[left] || Math.abs(heights[left] - height) > 2) {
                continue;
            }
            for (int second = first + 1; second < DX.length; second++) {
                int right = neighbor(cell, second);
                if (isGroup(right, CURB) && attachedCurbs[right] && Math.abs(heights[right] - height) <= 2
                        && DX[first] * DX[second] + DZ[first] * DZ[second] <= 0) {
                    return left;
                }
            }
        }
        return -1;
    }

    private void alignPaths(int[] heights, int[] baseHeights, boolean[] attachedCurbs) {
        for (int cell = 0; cell < surfaces.length; cell++) {
            if (!isGroup(cell, PATH) || attachedCurbs[cell]) {
                continue;
            }
            int best = -1;
            int bestScore = Integer.MAX_VALUE;
            // A curb touching only at a corner can belong to the next cross-section of a bend.
            for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
                int curb = neighbor(cell, direction);
                if (curb < 0 || !attachedCurbs[curb] || !canAlign(cell, heights[curb])) {
                    continue;
                }
                // At corners a neighboring curb can be part of the next terrace.
                if (heights[curb] < baseHeights[cell] || heights[curb] > baseHeights[cell] + 1) {
                    continue;
                }
                int score = Math.abs(heights[cell] - heights[curb]);
                if (score < bestScore) {
                    bestScore = score;
                    best = curb;
                }
            }
            if (best >= 0) {
                heights[cell] = heights[best];
            }
        }
    }

    private void closeSidewalkSeams(int[] heights, boolean[] attachedCurbs) {
        for (int curb = 0; curb < surfaces.length; curb++) {
            if (!attachedCurbs[curb] || hasMatchingPath(curb, -1, heights, attachedCurbs)) {
                continue;
            }
            int height = heights[curb];
            for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
                int path = neighbor(curb, direction);
                if (isGroup(path, PATH) && !attachedCurbs[path] && Math.abs(height - heights[path]) == 1
                        && canAlign(path, height) && blendsWithPath(path, height, heights, attachedCurbs)) {
                    heights[path] = height;
                    break;
                }
            }
        }
    }

    private boolean blendsWithPath(int path, int height, int[] heights, boolean[] attachedCurbs) {
        for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
            int adjacent = neighbor(path, direction);
            if (adjacent < 0) {
                continue;
            }
            if (!attachedCurbs[adjacent] && isGroup(adjacent, PATH)
                    && Math.abs(height - heights[adjacent]) > Math.max(1, Math.abs(heights[path] - heights[adjacent]))) {
                return false;
            }
            if (attachedCurbs[adjacent] && heights[adjacent] == heights[path]
                    && !hasMatchingPath(adjacent, path, heights, attachedCurbs)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasMatchingPath(int curb, int excludedPath, int[] heights, boolean[] attachedCurbs) {
        for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
            int path = neighbor(curb, direction);
            if (path != excludedPath && isGroup(path, PATH) && !attachedCurbs[path] && heights[path] == heights[curb]) {
                return true;
            }
        }
        return false;
    }

    private int roadAcrossCurb(int cell, int[] heights) {
        int inwardX = 0;
        int inwardZ = 0;
        int bestScore = Integer.MAX_VALUE;
        int best = -1;
        for (int direction = 0; direction < DX.length; direction++) {
            int candidate = neighbor(cell, direction);
            if (!isGroup(candidate, ROAD) || !canAlign(cell, heights[candidate] + 1)) {
                continue;
            }
            inwardX += DX[direction];
            inwardZ += DZ[direction];
            int score = 2 * Math.abs(surfaces[candidate].height() + 1 - surfaces[cell].height())
                    + (direction < CARDINAL_COUNT ? 0 : 1);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        // At a jog, one face neighbor can be farther along the slope and another across the curb.
        int dx = Math.abs(inwardX) > Math.abs(inwardZ) ? Integer.signum(inwardX) : 0;
        int dz = Math.abs(inwardZ) > Math.abs(inwardX) ? Integer.signum(inwardZ) : 0;
        if (dx != 0 || dz != 0) {
            int across = neighbor(cell, dx, dz);
            if (isGroup(across, ROAD) && canAlign(cell, heights[across] + 1)) {
                return across;
            }
        }
        return best;
    }

    private boolean canAlign(int cell, int height) {
        return height >= minHeight && height <= maxHeight
                && (surfaces[cell] == null || Math.abs(surfaces[cell].height() - height) <= 2);
    }

    private boolean isGroup(int cell, String group) {
        return cell >= 0 && surfaces[cell] != null && group.equals(surfaces[cell].group());
    }

    private int neighbor(int cell, int direction) {
        return neighbor(cell, DX[direction], DZ[direction]);
    }

    private int neighbor(int cell, int dx, int dz) {
        int z = cell % length + dz;
        int index = cell + dx * length + dz;
        return z >= 0 && z < length && index >= 0 && index < surfaces.length ? index : -1;
    }

}

