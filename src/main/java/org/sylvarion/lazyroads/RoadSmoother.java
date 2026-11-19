package org.sylvarion.lazyroads;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static com.fastasyncworldedit.core.registry.state.PropertyKey.TYPE;
import static com.fastasyncworldedit.core.registry.state.PropertyKey.WATERLOGGED;

final class RoadSmoother {

    private final Map<BlockType, RoadMaterial> materials = new HashMap<>();

    RoadSmoother(ConfigurationSection config, Logger logger) {
        ConfigurationSection palette = config.getConfigurationSection("road-materials");
        if (palette == null) {
            logger.warning("No road-materials are configured. //roadsmooth will find no road blocks.");
            return;
        }

        for (String groupName : palette.getKeys(false)) {
            ConfigurationSection group = palette.getConfigurationSection(groupName);
            if (group == null) {
                continue;
            }
            for (String sourceId : group.getKeys(false)) {
                String slabId = group.getString(sourceId, "");
                BlockType source = BlockTypes.get(sourceId);
                BlockType slab = slabId.isBlank() ? null : BlockTypes.get(slabId);
                if (source == null || slab == null || !slab.id().endsWith("_slab") || !slab.hasProperty(TYPE)) {
                    logger.warning("Skipping invalid road material mapping " + sourceId + " -> " + slabId);
                    continue;
                }

                BlockState state = slab.getDefaultState();
                BlockState fullBlock = source.id().endsWith("_slab")
                        ? source.getDefaultState().with(TYPE, "double")
                        : source.getDefaultState();
                RoadMaterial material = new RoadMaterial(groupName, state.with(TYPE, "bottom"), fullBlock);
                materials.putIfAbsent(slab, material);
                materials.put(source, material);
            }
        }
    }

    int smooth(EditSession edit, Region region, boolean preview) throws WorldEditException {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int length = region.getLength();
        int worldTop = edit.getMaximumPoint().y();
        Surface[] surfaces = new Surface[region.getWidth() * length];
        for (int index = 0; index < surfaces.length; index++) {
            surfaces[index] = findSurface(edit, region, min.x() + index / length, min.z() + index % length,
                    min.y(), max.y(), worldTop);
        }

        int maxHeight = 2 * max.y() + 2;
        int minHeight = 2 * min.y() + 1;
        RoadEdges edges = new RoadEdges(surfaces, length, minHeight, maxHeight);
        // Once an assembled road has half-block steps, repair its seams without refitting the terraces.
        RoadTransitions transitions = edges.hasContinuousGrade() ? null : new RoadTransitions(surfaces, length);
        int[] heights = transitions == null ? new int[surfaces.length] : transitions.find();
        RoadMaterial[] targetMaterials = new RoadMaterial[surfaces.length];
        for (int index = 0; index < surfaces.length; index++) {
            Surface surface = surfaces[index];
            if (surface != null) {
                heights[index] = transitions == null ? surface.height() : Math.clamp(heights[index], minHeight, maxHeight);
                targetMaterials[index] = surface.material();
            }
        }
        edges.align(heights, transitions == null ? null : transitions.baseHeights(), targetMaterials,
                (cell, height) -> clearColumn(edit, region, min.x() + cell / length, min.z() + cell % length,
                        blockY(height), max.y()));

        int count = 0;
        for (int index = 0; index < surfaces.length; index++) {
            Surface surface = surfaces[index];
            RoadMaterial material = targetMaterials[index];
            int height = heights[index];
            if (material == null || (surface != null && surface.height() == height && surface.material() == material)) {
                continue;
            }
            if (applyColumn(edit, region, min.x() + index / length, min.z() + index % length,
                    surface, material, height, preview)) {
                count++;
            }
        }
        return count;
    }

    private boolean applyColumn(EditSession edit, Region region, int x, int z, Surface surface, RoadMaterial material,
                                int height, boolean preview) throws WorldEditException {
        int y = blockY(height);
        BlockVector3 target = BlockVector3.at(x, y, z);
        if (!region.contains(target)) {
            return false;
        }
        BlockState cap = (height & 1) == 0 ? material.fullBlock() : material.bottomSlab();
        if (surface == null || surface.y() == y) {
            return preview || edit.setBlock(target, cap);
        }

        BlockVector3 oldPosition = BlockVector3.at(x, surface.y(), z);
        BlockState newBase;
        if (y > surface.y()) {
            newBase = material.fullBlock();
        } else {
            BlockType below = edit.getBlock(target).getBlockType();
            RoadMaterial belowMaterial = materials.get(below);
            if (!below.getMaterial().isAir() && (belowMaterial == null
                    || (!belowMaterial.group().equals(surface.group()) && !belowMaterial.group().equals(material.group())))) {
                return false;
            }
            newBase = BlockTypes.AIR.getDefaultState();
        }

        if (preview) {
            return true;
        }
        // Validate both positions before a two-block edit, and leave the old cap in place if lowering fails.
        if (edit.getMask() != null && (!edit.getMask().test(target) || !edit.getMask().test(oldPosition))) {
            return false;
        }
        if (!cap.equals(edit.getBlock(target)) && !edit.setBlock(target, cap)) {
            return false;
        }
        if (!newBase.equals(edit.getBlock(oldPosition))) {
            edit.setBlock(oldPosition, newBase);
        }
        return true;
    }

    private boolean clearColumn(EditSession edit, Region region, int x, int z, int startY, int maxY) {
        if (!region.contains(BlockVector3.at(x, startY, z))) {
            return false;
        }
        int top = Math.min(maxY + 1, edit.getMaximumPoint().y());
        for (int y = startY; y <= top; y++) {
            if (!edit.getBlock(x, y, z).getBlockType().getMaterial().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static int blockY(int height) {
        return Math.floorDiv(height - 1, 2);
    }
    private Surface findSurface(EditSession edit, Region region, int x, int z, int minY, int maxY, int worldTop) {
        RoadSurface surface = RoadSurface.find(edit, region, x, z, minY, maxY, worldTop);
        if (surface == null) {
            return null;
        }
        BlockState state = surface.state();
        BlockType type = state.getBlockType();
        RoadMaterial material = materials.get(type);
        if (material == null || (type.hasProperty(WATERLOGGED) && Boolean.TRUE.equals(state.getState(WATERLOGGED)))) {
            return null;
        }
        boolean bottomSlab = type.id().endsWith("_slab") && "bottom".equals(state.getState(TYPE));
        return new Surface(surface.y(), bottomSlab, material);
    }
    record RoadMaterial(String group, BlockState bottomSlab, BlockState fullBlock) {}

    record Surface(int y, boolean bottomSlab, RoadMaterial material) {
        String group() {
            return material.group();
        }

        int height() {
            return 2 * y + (bottomSlab ? 1 : 2);
        }
    }
}



