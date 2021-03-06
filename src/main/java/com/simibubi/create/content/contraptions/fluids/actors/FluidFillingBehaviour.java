package com.simibubi.create.content.contraptions.fluids.actors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.simibubi.create.foundation.tileEntity.SmartTileEntity;
import com.simibubi.create.foundation.tileEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.utility.Iterate;

import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue;
import net.minecraft.block.BlockState;
import net.minecraft.block.FlowingFluidBlock;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.fluid.IFluidState;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.world.ITickList;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerTickList;

public class FluidFillingBehaviour extends FluidManipulationBehaviour {

	PriorityQueue<BlockPosEntry> queue;

	List<BlockPosEntry> infinityCheckFrontier;
	Set<BlockPos> infinityCheckVisited;

	public FluidFillingBehaviour(SmartTileEntity te) {
		super(te);
		queue = new ObjectHeapPriorityQueue<>((p, p2) -> -comparePositions(p, p2));
		revalidateIn = 1;
		infinityCheckFrontier = new ArrayList<>();
		infinityCheckVisited = new HashSet<>();
	}

	@Override
	public void tick() {
		super.tick();
		if (!infinityCheckFrontier.isEmpty() && rootPos != null) {
			Fluid fluid = getWorld().getFluidState(rootPos)
				.getFluid();
			if (fluid != Fluids.EMPTY)
				continueValidation(fluid);
		}
		if (revalidateIn > 0)
			revalidateIn--;
	}

	protected void continueValidation(Fluid fluid) {
		search(fluid, infinityCheckFrontier, infinityCheckVisited,
			(p, d) -> infinityCheckFrontier.add(new BlockPosEntry(p, d)), true);
		int maxBlocks = maxBlocks();

		if (infinityCheckVisited.size() > maxBlocks) {
			if (!infinite) {
				reset();
				infinite = true;
			}
			infinityCheckFrontier.clear();
			setLongValidationTimer();
			return;
		}

		if (!infinityCheckFrontier.isEmpty())
			return;
		if (infinite) {
			reset();
			return;
		}

		infinityCheckVisited.clear();
	}

	public boolean tryDeposit(Fluid fluid, BlockPos root, boolean simulate) {
		if (!Objects.equals(root, rootPos)) {
			reset();
			rootPos = root;
			queue.enqueue(new BlockPosEntry(root, 0));
			affectedArea = new MutableBoundingBox(rootPos, rootPos);
			return false;
		}

		if (counterpartActed) {
			counterpartActed = false;
			softReset(root);
			return false;
		}

		if (affectedArea == null)
			affectedArea = new MutableBoundingBox(root, root);

		if (revalidateIn == 0) {
			visited.clear();
			infinityCheckFrontier.clear();
			infinityCheckVisited.clear();
			infinityCheckFrontier.add(new BlockPosEntry(root, 0));
			setValidationTimer();
			softReset(root);
		}

		World world = getWorld();
		int maxRange = maxRange();
		int maxRangeSq = maxRange * maxRange;
		int maxBlocks = maxBlocks();

		if (infinite) {
			IFluidState fluidState = world.getFluidState(rootPos);
			boolean equivalentTo = fluidState.getFluid()
				.isEquivalentTo(fluid);
			if (!equivalentTo)
				return false;
			if (simulate)
				return true;
			playEffect(world, BlockPos.ZERO, fluid, false);
			return true;
		}

		boolean success = false;
		for (int i = 0; !success && !queue.isEmpty() && i < searchedPerTick; i++) {
			BlockPosEntry entry = queue.first();
			BlockPos currentPos = entry.pos;

			if (visited.contains(currentPos)) {
				queue.dequeue();
				continue;
			}

			if (!simulate)
				visited.add(currentPos);

			if (visited.size() >= maxBlocks) {
				infinite = true;
				visited.clear();
				queue.clear();
				return false;
			}

			SpaceType spaceType = getAtPos(world, currentPos, fluid);
			if (spaceType == SpaceType.BLOCKING)
				continue;
			if (spaceType == SpaceType.FILLABLE) {
				success = true;
				if (!simulate) {
					playEffect(world, currentPos, fluid, false);

					BlockState blockState = world.getBlockState(currentPos);
					if (blockState.has(BlockStateProperties.WATERLOGGED) && fluid.isEquivalentTo(Fluids.WATER)) {
						world.setBlockState(currentPos, blockState.with(BlockStateProperties.WATERLOGGED, true),
							2 | 16);
					} else {
						world.setBlockState(currentPos, fluid.getDefaultState()
							.getBlockState(), 2 | 16);
					}

					ITickList<Fluid> pendingFluidTicks = world.getPendingFluidTicks();
					if (pendingFluidTicks instanceof ServerTickList) {
						ServerTickList<Fluid> serverTickList = (ServerTickList<Fluid>) pendingFluidTicks;
						NextTickListEntry<Fluid> removedEntry = null;
						for (NextTickListEntry<Fluid> nextTickListEntry : serverTickList.pendingTickListEntriesHashSet) {
							if (nextTickListEntry.position.equals(currentPos)) {
								removedEntry = nextTickListEntry;
								break;
							}
						}
						if (removedEntry != null) {
							serverTickList.pendingTickListEntriesHashSet.remove(removedEntry);
							serverTickList.pendingTickListEntriesTreeSet.remove(removedEntry);
						}
					}

					affectedArea.expandTo(new MutableBoundingBox(currentPos, currentPos));
				}
			}

			if (simulate && success)
				return true;

			visited.add(currentPos);
			queue.dequeue();

			for (Direction side : Iterate.directions) {
				if (side == Direction.UP)
					continue;

				BlockPos offsetPos = currentPos.offset(side);
				if (visited.contains(offsetPos))
					continue;
				if (offsetPos.distanceSq(rootPos) > maxRangeSq)
					continue;

				SpaceType nextSpaceType = getAtPos(world, offsetPos, fluid);
				if (nextSpaceType != SpaceType.BLOCKING)
					queue.enqueue(new BlockPosEntry(offsetPos, entry.distance + 1));
			}
		}

		return success;
	}

	protected void softReset(BlockPos root) {
		visited.clear();
		queue.clear();
		queue.enqueue(new BlockPosEntry(root, 0));
		infinite = false;
		setValidationTimer();
	}

	enum SpaceType {
		FILLABLE, FILLED, BLOCKING
	}

	protected SpaceType getAtPos(World world, BlockPos pos, Fluid toFill) {
		BlockState blockState = world.getBlockState(pos);
		IFluidState fluidState = blockState.getFluidState();

		if (blockState.has(BlockStateProperties.WATERLOGGED))
			return toFill.isEquivalentTo(Fluids.WATER)
				? blockState.get(BlockStateProperties.WATERLOGGED) ? SpaceType.FILLED : SpaceType.FILLABLE
				: SpaceType.BLOCKING;

		if (blockState.getBlock() instanceof FlowingFluidBlock)
			return blockState.get(FlowingFluidBlock.LEVEL) == 0
				? toFill.isEquivalentTo(fluidState.getFluid()) ? SpaceType.FILLED : SpaceType.BLOCKING
				: SpaceType.FILLABLE;

		if (fluidState.getFluid() != Fluids.EMPTY
			&& blockState.getCollisionShape(getWorld(), pos, ISelectionContext.dummy())
				.isEmpty())
			return toFill.isEquivalentTo(fluidState.getFluid()) ? SpaceType.FILLED : SpaceType.BLOCKING;

		return blockState.getMaterial()
			.isReplaceable() ? SpaceType.FILLABLE : SpaceType.BLOCKING;
	}

	@Override
	public void reset() {
		super.reset();
		queue.clear();
		infinityCheckFrontier.clear();
		infinityCheckVisited.clear();
	}

	public static BehaviourType<FluidFillingBehaviour> TYPE = new BehaviourType<>();

	@Override
	public BehaviourType<?> getType() {
		return TYPE;
	}

}
