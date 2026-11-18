package org.sylvarion.lazyroads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class RoadCenterline {
    private static final int[] DX = {-1, 0, 0, 1, -1, -1, 1, 1};
    private static final int[] DZ = {0, -1, 1, 0, -1, 1, -1, 1};
    private static final int CARDINAL_COUNT = 4;

    private final RoadSurface[] surfaces;
    private final int[] heights;
    private final int length;
    private final int[] component;
    private final int[] queue;
    private final int[] distance;
    private final int[] clearance;
    private final int[] previous;
    private final long[] cost;

    RoadCenterline(RoadSurface[] surfaces, int[] heights, int length) {
        this.surfaces = surfaces;
        this.heights = heights;
        this.length = length;
        component = new int[surfaces.length];
        queue = new int[surfaces.length];
        distance = new int[surfaces.length];
        clearance = new int[surfaces.length];
        previous = new int[surfaces.length];
        cost = new long[surfaces.length];
    }

    List<int[]> find(boolean[] marked) {
        var lines = new ArrayList<int[]>();
        boolean[] visited = new boolean[surfaces.length];
        boolean[] onLine = new boolean[surfaces.length];
        for (int start = 0; start < surfaces.length; start++) {
            if (surfaces[start] == null || visited[start]) {
                continue;
            }

            int size = collect(start, visited);
            boolean hasMarking = false;
            for (int i = 0; i < size; i++) {
                hasMarking |= marked[component[i]];
            }
            if (hasMarking) {
                continue;
            }

            int widest = measureClearance(size);
            // Walk to opposite ends, then recenter the anchors so a corner does not pull the line sideways.
            int firstTip = walk(start, size);
            int secondTip = walk(firstTip, size);
            int first = centerNearTip(size, widest);
            walk(secondTip, size);
            int second = centerNearTip(size, widest);
            if (first == second) {
                // A short, nearly square patch does not have two distinct center anchors.
                continue;
            }
            if (firstTip < secondTip) {
                lines.add(trace(first, second, size, onLine));
            } else {
                lines.add(trace(second, first, size, onLine));
            }
        }
        return lines;
    }

    private int collect(int start, boolean[] visited) {
        int size = 1;
        component[0] = start;
        visited[start] = true;
        for (int i = 0; i < size; i++) {
            int cell = component[i];
            for (int direction = 0; direction < DX.length; direction++) {
                int next = neighbor(cell, direction);
                if (canTravel(cell, next) && !visited[next]) {
                    visited[next] = true;
                    component[size++] = next;
                }
            }
        }
        return size;
    }

    private int measureClearance(int size) {
        int end = 0;
        for (int i = 0; i < size; i++) {
            int cell = component[i];
            for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
                if (!canTravel(cell, neighbor(cell, direction))) {
                    clearance[cell] = 1;
                    queue[end++] = cell;
                    break;
                }
            }
        }

        int widest = 1;
        for (int i = 0; i < end; i++) {
            int cell = queue[i];
            widest = Math.max(widest, clearance[cell]);
            for (int direction = 0; direction < CARDINAL_COUNT; direction++) {
                int next = neighbor(cell, direction);
                if (canTravel(cell, next) && clearance[next] == 0) {
                    clearance[next] = clearance[cell] + 1;
                    queue[end++] = next;
                }
            }
        }
        return widest;
    }

    private int walk(int start, int size) {
        for (int i = 0; i < size; i++) {
            distance[component[i]] = -1;
        }
        int farthest = start;
        int end = 1;
        queue[0] = start;
        distance[start] = 0;
        for (int i = 0; i < end; i++) {
            int cell = queue[i];
            if (distance[cell] > distance[farthest] || (distance[cell] == distance[farthest] && cell < farthest)) {
                farthest = cell;
            }
            for (int direction = 0; direction < DX.length; direction++) {
                int next = neighbor(cell, direction);
                if (canTravel(cell, next) && distance[next] < 0) {
                    distance[next] = distance[cell] + 1;
                    queue[end++] = next;
                }
            }
        }
        return farthest;
    }

    private int centerNearTip(int size, int widest) {
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            int cell = component[i];
            if (distance[cell] > 2 * widest) {
                continue;
            }
            int score = distance[cell] - 3 * clearance[cell];
            if (score < bestScore || (score == bestScore
                    && (clearance[cell] > clearance[best] || (clearance[cell] == clearance[best] && cell < best)))) {
                best = cell;
                bestScore = score;
            }
        }
        return best;
    }

    private int[] trace(int start, int finish, int size, boolean[] onLine) {
        for (int i = 0; i < size; i++) {
            cost[component[i]] = Long.MAX_VALUE;
        }
        cost[start] = 0;
        var open = new PriorityQueue<Visit>(Comparator.comparingLong(Visit::cost).thenComparingInt(Visit::cell));
        open.add(new Visit(start, 0));
        while (!open.isEmpty()) {
            Visit visit = open.remove();
            int cell = visit.cell();
            if (visit.cost() != cost[cell]) {
                continue;
            }
            if (cell == finish) {
                break;
            }
            for (int direction = 0; direction < DX.length; direction++) {
                int next = neighbor(cell, direction);
                if (!canTravel(cell, next)) {
                    continue;
                }
                int step = direction < CARDINAL_COUNT ? 10 : 14;
                // Make a shortcut around the inside of a bend more expensive near the pavement edge.
                long nextCost = visit.cost() + step * (10 + 60 / clearance[next]);
                if (nextCost < cost[next]) {
                    cost[next] = nextCost;
                    previous[next] = cell;
                    open.add(new Visit(next, nextCost));
                }
            }
        }

        var middle = new ArrayList<Integer>();
        for (int cell = finish; cell != start; cell = previous[cell]) {
            middle.add(cell);
            onLine[cell] = true;
        }
        middle.add(start);
        onLine[start] = true;

        var front = extend(start, middle.get(Math.max(0, middle.size() - clearance[start] - 1)), onLine);
        var back = extend(finish, middle.get(Math.min(middle.size() - 1, clearance[finish])), onLine);
        Collections.reverse(front);
        Collections.reverse(middle);
        front.addAll(middle);
        front.addAll(back);
        return front.stream().mapToInt(Integer::intValue).toArray();
    }

    private List<Integer> extend(int start, int inward, boolean[] onLine) {
        var extension = new ArrayList<Integer>();
        int startX = start / length;
        int startZ = start % length;
        int dx = startX - inward / length;
        int dz = startZ - inward % length;
        int scale = Math.max(Math.abs(dx), Math.abs(dz));
        int width = surfaces.length / length;
        int last = start;
        for (int step = 1; step <= 2 * clearance[start] + 1 && scale != 0; step++) {
            int x = startX + (int) Math.round(step * (double) dx / scale);
            int z = startZ + (int) Math.round(step * (double) dz / scale);
            if (x < 0 || x >= width || z < 0 || z >= length) {
                break;
            }
            int cell = x * length + z;
            if (!canTravel(last, cell) || onLine[cell]) {
                break;
            }
            extension.add(cell);
            onLine[cell] = true;
            last = cell;
        }
        return extension;
    }

    private boolean canTravel(int cell, int next) {
        return next >= 0 && surfaces[next] != null && Math.abs(heights[cell] - heights[next]) <= 2;
    }

    private int neighbor(int cell, int direction) {
        int z = cell % length + DZ[direction];
        int next = cell + DX[direction] * length + DZ[direction];
        return z >= 0 && z < length && next >= 0 && next < surfaces.length ? next : -1;
    }

    private record Visit(int cell, long cost) {}
}
