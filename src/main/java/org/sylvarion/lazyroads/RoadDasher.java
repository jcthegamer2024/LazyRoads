package org.sylvarion.lazyroads;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import static com.fastasyncworldedit.core.registry.state.PropertyKey.TYPE;
import static com.fastasyncworldedit.core.registry.state.PropertyKey.WATERLOGGED;

final class RoadDasher {
    private final int dashLength;
    private final long period;

    RoadDasher(int dashLength, int gapLength) {
        this.dashLength = dashLength;
        this.period = (long) dashLength + gapLength;
    }

    int dash(EditSession edit, Region region, boolean preview) throws WorldEditException {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int width = region.getWidth();
        int length = region.getLength();
        int worldTop = edit.getMaximumPoint().y();
        RoadSurface[] surfaces = new RoadSurface[width * length];
        int[] heights = new int[surfaces.length];
        boolean[] marked = new boolean[surfaces.length];
        for (int cell = 0; cell < surfaces.length; cell++) {
            RoadSurface surface = RoadSurface.find(edit, region, min.x() + cell / length, min.z() + cell % length,
                    min.y(), max.y(), worldTop);
            if (surface == null) {
                continue;
            }
            BlockState state = surface.state();
            BlockType type = state.getBlockType();
            boolean white = type == BlockTypes.WHITE_CONCRETE || type == BlockTypes.WHITE_CONCRETE_SLAB;
            boolean slab = type == BlockTypes.POLISHED_DEEPSLATE_SLAB || type == BlockTypes.WHITE_CONCRETE_SLAB;
            if (!white && type != BlockTypes.POLISHED_DEEPSLATE && !slab) {
                continue;
            }
            if (slab && !white && Boolean.TRUE.equals(state.getState(WATERLOGGED))) {
                continue;
            }
            surfaces[cell] = surface;
            heights[cell] = 2 * surface.y() + (slab && "bottom".equals(state.getState(TYPE)) ? 1 : 2);
            marked[cell] = white;
        }

        int count = 0;
        for (int[] line : new RoadCenterline(surfaces, heights, length).find(marked)) {
            for (int step = 0; step < line.length; step++) {
                if (step % period >= dashLength) {
                    continue;
                }
                int cell = line[step];
                RoadSurface surface = surfaces[cell];
                BlockState white = BlockTypes.WHITE_CONCRETE.getDefaultState();
                if (surface.state().getBlockType() == BlockTypes.POLISHED_DEEPSLATE_SLAB) {
                    white = BlockTypes.WHITE_CONCRETE_SLAB.getDefaultState()
                            .with(TYPE, surface.state().<String>getState(TYPE));
                }
                BlockVector3 position = BlockVector3.at(min.x() + cell / length, surface.y(), min.z() + cell % length);
                if (preview || edit.setBlock(position, white)) {
                    count++;
                }
            }
        }
        return count;
    }
}
