package com.terraforged.fm.template;

import com.terraforged.fm.util.BlockReader;
import com.terraforged.fm.util.ObjectPool;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorld;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.feature.Feature;
import net.minecraftforge.common.util.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class Template {

    private final List<Template.BlockInfo> blocks;

    public Template(List<BlockInfo> blocks) {
        this.blocks = blocks;
    }

    public boolean paste(IWorld world, BlockPos origin, Mirror mirror, Rotation rotation, PasteConfig config) {
        if (config.checkBounds) {
            IChunk chunk = world.getChunk(origin);
            if (chunk == null || chunk.getStructureReferences(Feature.VILLAGE.getStructureName()).isEmpty()) {
                return pasteNormal(world, origin, mirror, rotation, config);
            }
            return pasteWithBoundsCheck(world, origin, mirror, rotation, config);
        } else {
            return pasteNormal(world, origin, mirror, rotation, config);
        }
    }

    public boolean pasteNormal(IWorld world, BlockPos origin, Mirror mirror, Rotation rotation, PasteConfig config) {
        boolean placed = false;
        BlockReader reader = new BlockReader();
        for (Template.BlockInfo block : blocks) {
            BlockState state = block.state.mirror(mirror).rotate(rotation);
            if (!config.pasteAir && state.getBlock() == Blocks.AIR) {
                continue;
            }

            BlockPos pos = transform(block.pos, mirror, rotation).add(origin);
            if (!config.replaceSolid && world.getBlockState(pos).isSolid()) {
                continue;
            }

            if (block.pos.getY() <= 0 && block.state.isNormalCube(reader.setState(block.state), BlockPos.ZERO)) {
                placeBase(world, pos, block.state, config.baseDepth);
            }

            placed = true;
            world.setBlockState(pos, block.state, 2);
        }
        return placed;
    }

    public boolean pasteWithBoundsCheck(IWorld world, BlockPos origin, Mirror mirror, Rotation rotation, PasteConfig config) {
        try (ObjectPool.Item<TemplateBuffer> item = TemplateBuffer.pooled()) {
            BlockReader reader = new BlockReader();
            TemplateBuffer buffer = item.getValue().init(world, origin);

            for (Template.BlockInfo block : blocks) {
                BlockState state = block.state.mirror(mirror).rotate(rotation);
                BlockPos pos = origin.add(transform(block.pos, mirror, rotation));
                buffer.record(pos, state, config);
            }

            boolean placed = false;
            for (Template.BlockInfo block : buffer.getBlocks()) {
                if (block.pos.getY() <= origin.getY() && block.state.isNormalCube(reader.setState(block.state), BlockPos.ZERO)) {
                    placeBase(world, block.pos, block.state, config.baseDepth);
                    world.setBlockState(block.pos, block.state, 2);
                    placed = true;
                } else if (buffer.test(block.pos)) {
                    placed = true;
                    world.setBlockState(block.pos, block.state, 2);
                }
            }

            buffer.flush();
            return placed;
        }
    }

    private void placeBase(IWorld world, BlockPos pos, BlockState state, int depth) {
        for (int dy = 0; dy < depth; dy++) {
            pos = pos.down();
            if (world.getBlockState(pos).isSolid()) {
                return;
            }
            world.setBlockState(pos, state, 2);
        }
    }

    public static BlockPos transform(BlockPos pos, Mirror mirror, Rotation rotation) {
        return net.minecraft.world.gen.feature.template.Template.getTransformedPos(pos, mirror, rotation, BlockPos.ZERO);
    }

    public static class BlockInfo {

        private final BlockPos pos;
        private final BlockState state;

        public BlockInfo(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }

        public BlockPos getPos() {
            return pos;
        }

        public BlockState getState() {
            return state;
        }

        @Override
        public String toString() {
            return state.toString();
        }
    }

    public static Optional<Template> load(InputStream data) {
        try {
            CompoundNBT root = CompressedStreamTools.readCompressed(data);
            if (!root.contains("palette") || !root.contains("blocks")) {
                return Optional.empty();
            }
            BlockState[] palette = readPalette(root.getList("palette", Constants.NBT.TAG_COMPOUND));
            Template.BlockInfo[] blockInfos = readBlocks(root.getList("blocks", Constants.NBT.TAG_COMPOUND), palette);
            List<Template.BlockInfo> blocks = relativize(blockInfos);
            return Optional.of(new Template(blocks));
        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private static BlockState[] readPalette(ListNBT list) {
        BlockState[] palette = new BlockState[list.size()];
        for (int i = 0; i < list.size(); i++) {
            try {
                palette[i] = NBTUtil.readBlockState(list.getCompound(i));
            } catch (Throwable t) {
                palette[i] = Blocks.AIR.getDefaultState();
            }
        }
        return palette;
    }

    private static Template.BlockInfo[] readBlocks(ListNBT list, BlockState[] palette) {
        Template.BlockInfo[] blocks = new Template.BlockInfo[list.size()];
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT compound = list.getCompound(i);
            BlockState state = palette[compound.getInt("state")];
            BlockPos pos = readPos(compound.getList("pos", Constants.NBT.TAG_INT));
            blocks[i] = new Template.BlockInfo(pos, state);
        }
        return blocks;
    }

    private static List<Template.BlockInfo> relativize(Template.BlockInfo[] blocks) {
        // find the lowest, most-central block (the origin)
        BlockPos origin = null;
        int lowestSolid = Integer.MAX_VALUE;

        for (Template.BlockInfo block : blocks) {
            if (!block.state.isSolid()) {
                continue;
            }

            if (origin == null) {
                origin = block.pos;
                lowestSolid = block.pos.getY();
            }else if (block.pos.getY() < lowestSolid) {
                origin = block.pos;
                lowestSolid = block.pos.getY();
            } else if (block.pos.getY() == lowestSolid) {
                if (block.pos.getX() < origin.getX() && block.pos.getZ() <= origin.getZ()) {
                    origin = block.pos;
                    lowestSolid = block.pos.getY();
                } else if (block.pos.getZ() < origin.getZ() && block.pos.getX() <= origin.getX()) {
                    origin = block.pos;
                    lowestSolid = block.pos.getY();
                }
            }
        }

        if (origin == null) {
            return Arrays.asList(blocks);
        }

        // relativize all blocks to the origin
        List<Template.BlockInfo> list = new ArrayList<>(blocks.length);
        for (Template.BlockInfo in : blocks) {
            BlockPos pos = in.pos.subtract(origin);
            list.add(new Template.BlockInfo(pos, in.state));
        }

        return list;
    }

    private static int dist2(int x1, int z1, int x2, int z2) {
        int dx = x1 - x2;
        int dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private static BlockPos readPos(ListNBT list) {
        int x = list.getInt(0);
        int y = list.getInt(1);
        int z = list.getInt(2);
        return new BlockPos(x, y, z);
    }
}