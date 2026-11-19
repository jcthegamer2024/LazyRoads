package org.sylvarion.lazyroads;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;

record RoadSurface(int y, BlockState state) {
    static RoadSurface find(EditSession edit, Region region, int x, int z, int minY, int maxY, int worldTop) {
        if (!region.contains(BlockVector3.at(x, minY, z))) {
            return null;
        }
        if (maxY < worldTop && !edit.getBlock(x, maxY + 1, z).getBlockType().getMaterial().isAir()) {
            return null;
        }
        for (int y = maxY; y >= minY; y--) {
            BlockState state = edit.getBlock(x, y, z);
            if (!state.getBlockType().getMaterial().isAir()) {
                return new RoadSurface(y, state);
            }
        }
        return null;
    }
}
