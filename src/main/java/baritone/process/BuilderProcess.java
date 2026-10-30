/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final Set<Property<?>> ORIENTATION_PROPS =
            ImmutableSet.of(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            );

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    public int stopAtHeight = 0;
    // hb: standing spots left out of the goal, see noteIdleSpot
    private static final int IDLE_SPOT_TICKS = 100;
    private final Set<BetterBlockPos> idleSpots = new HashSet<>();
    private BetterBlockPos idleAt;
    private int idleTicks;
    private long lastToDo = -1;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        this.placeSpots.clear(); // hb
        this.toSet.clear(); // hb
        this.props.clear(); // hb
        this.clearGivenUp.clear(); // hb
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        this.idleSpots.clear(); // hb
        this.idleAt = null;
        this.idleTicks = 0;
        this.lastToDo = -1;
    }

    public void resume() {
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Pair<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().first();
                BlockPos origin = schematic.get().second();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Pair<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.second();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.first());
                build(schematic.first().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        // hb: a movement places this to stand on; a block that is no footing (a hopper, a chest) would
        // be broken again by the movement and put back by the builder, round and round, and one that
        // takes its facing from the placing (a jack o'lantern, a log) goes down facing however the
        // movement happens to look: a throwaway
        if (!MovementHelper.canWalkOn(baritone.bsi, x, y, z, state) || facesAsPlaced(state)) {
            return null;
        }
        return state;
    }

    // hb: see breakCostMultiplierAt
    private static final double PLAIN_BREAK_PENALTY = 2;

    private static boolean plain(BlockState state) {
        return state.isSolidRender() && !state.hasBlockEntity() && !facesAsPlaced(state);
    }

    // hb: properties set by how a block is placed (the look, the face clicked), unless ignored
    private static final Set<String> PLACED_PROPS = Set.of("facing", "axis", "rotation", "half", "face", "type", "hinge", "attachment");

    private static boolean facesAsPlaced(BlockState state) {
        if (Baritone.settings().buildIgnoreDirection.value) {
            return false;
        }
        for (Property<?> prop : state.getProperties()) {
            if (PLACED_PROPS.contains(prop.getName())
                    && (!Baritone.settings().buildIgnoreProperties.value.contains(prop.getName()) || prop == SlabBlock.TYPE)) { // SLAB_TYPE_KEPT
                return true;
            }
        }
        return false;
    }

    private Optional<Pair<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        // With a chooser (Humanlike Baritone fork), collect every reachable block and let it pick
        IBreakChooser chooser = baritone.getBreakChooser();
        List<Pair<BetterBlockPos, Rotation>> reachable = chooser == null ? null : new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)) {
                        if (lavaAgainst(bcc, x, y, z)) {
                            // hb: never open a way for lava (Baritone's pathing has the same rule, avoidBreaking)
                            continue;
                        }
                        if (reachable != null && !(dx == 0 && dz == 0 && dy <= 1) && buried(bcc, x, y, z)) {
                            // no ray from outside reaches it; skips the raytraces for the whole scan (not
                            // the blocks the player stands in: a ray from the eye starts inside those)
                            continue;
                        }
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            if (reachable == null) {
                                return Optional.of(new Pair<>(pos, rot.get()));
                            }
                            reachable.add(new Pair<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        if (reachable == null || reachable.isEmpty()) {
            return Optional.empty();
        }
        int pick = chooser.choose(reachable.stream().map(Pair::first).toList());
        return Optional.of(reachable.get(pick >= 0 && pick < reachable.size() ? pick : 0));
    }

    /** Lava above or beside (x, y, z): breaking it would let the lava in. */
    private static boolean lavaAgainst(BuilderCalculationContext bcc, int x, int y, int z) {
        return MovementHelper.isLava(bcc.bsi.get0(x, y + 1, z))
                || MovementHelper.isLava(bcc.bsi.get0(x + 1, y, z)) || MovementHelper.isLava(bcc.bsi.get0(x - 1, y, z))
                || MovementHelper.isLava(bcc.bsi.get0(x, y, z + 1)) || MovementHelper.isLava(bcc.bsi.get0(x, y, z - 1));
    }

    private static boolean buried(BuilderCalculationContext bcc, int x, int y, int z) {
        return bcc.bsi.get0(x + 1, y, z).canOcclude() && bcc.bsi.get0(x - 1, y, z).canOcclude()
                && bcc.bsi.get0(x, y + 1, z).canOcclude() && bcc.bsi.get0(x, y - 1, z).canOcclude()
                && bcc.bsi.get0(x, y, z + 1).canOcclude() && bcc.bsi.get0(x, y, z - 1).canOcclude();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        // hb: the cells the path digs through, and those it meant to lately (between one path and the
        // next there's none, and the builder put the rock back in the gap)
        long now = ctx.world().getGameTime();
        for (BlockPos d : pathDigs()) {
            pathDug.put(d.immutable(), now);
        }
        pathDug.values().removeIf(t -> now - t > PATH_DUG_TICKS);
        Set<BlockPos> dugAhead = pathDug.keySet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    if (!dugAhead.isEmpty() && dugAhead.contains(new BlockPos(x, y, z))) {
                        continue; // hb: the path ahead digs through it: filled again once past
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        // hb: but a ladder (or a vine) at head height, under air, is how a shaft goes up: the
                        // player climbs the one placed and places the next, never otherwise
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock && !desired.is(BlockTags.CLIMBABLE)) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    // hb: how many steps of the path ahead keep their cells to dig clear of the builder's filling
    private static final int DIG_AHEAD_STEPS = 6;

    // hb: how long a cell a path meant to dig through stays unfilled after, ticks
    private static final int PATH_DUG_TICKS = 200;

    private final Map<BlockPos, Long> pathDug = new HashMap<>();

    /**
     * hb: the cells the next steps of the path dig through. The way up out of a ladder shaft went
     * through the rock beside it: the step broke it, the builder (the rock being wrong now, and in
     * reach) put it back, and the path went to break it again, for the rest of the attempt.
     */
    private Set<BlockPos> pathDigs() {
        baritone.api.pathing.path.IPathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null) {
            return Set.of();
        }
        Set<BlockPos> digs = new HashSet<>();
        List<baritone.api.pathing.movement.IMovement> movements = current.getPath().movements();
        for (int k = Math.max(0, current.getPosition()); k < Math.min(movements.size(), current.getPosition() + DIG_AHEAD_STEPS); k++) {
            if (movements.get(k) instanceof Movement m) {
                digs.addAll(Arrays.asList(m.toBreakAll()));
            }
        }
        return digs;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == against.getOpposite()
                        && (baritone.getIntentSink() == null || placesAround(toPlace, actualRot, placeAgainstPos, against.getOpposite()))) { // hb
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * hb: with an intent sink the camera lands on the face near the planned rotation, not on it, and a
     * block that takes its facing from the look (a chest, a piston, a repeater) or from the point
     * clicked (a slab) can come out wrong from there. True if the look as it is places the block the
     * schematic wants, with a hotbar slot that does, selected; else no click, and the sink adjusts.
     */
    private boolean placesAsWanted(Placement placement, BuilderCalculationContext bcc) {
        if (!(ctx.objectMouseOver() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos at = placement.placeAgainst.relative(placement.side);
        BlockState desired = bcc.getSchematic(at.getX(), at.getY(), at.getZ(), bcc.bsi.get0(at.getX(), at.getY(), at.getZ()));
        if (desired == null) {
            return true;
        }
        OptionalInt slot = hasAnyItemThatWouldPlace(desired, hit, ctx.playerRotations());
        slot.ifPresent(i -> ctx.player().getInventory().setSelectedSlot(i));
        if (slot.isPresent() && pairsChest(placement, bcc) && !chestPairs(desired, hit, slot.getAsInt(), at)) {
            // hb: CHEST_PAIRS: the other half is in, and from here (still sneaking) this one would go in
            // single: a moment, for the sneak to come off, but not for ever
            if (++chestWaits < CHEST_WAIT_TICKS) {
                return false;
            }
        } else {
            chestWaits = 0;
        }
        if (placeChecks++ % 20 == 0) {
            logDebug("Placing " + desired + " at " + at + " against " + placement.placeAgainst + " " + placement.side
                    + "; the crosshair is on " + hit.getBlockPos() + " " + hit.getDirection() + ", slot " + slot);
        }
        return slot.isPresent();
    }

    private int placeChecks; // hb: for the debug line above

    // hb: CHEST_PAIRS: how long a chest whose other half is in waits for the sneak to come off, ticks
    private static final int CHEST_WAIT_TICKS = 40;

    private int chestWaits;

    // hb: CHEST_PAIRS: a chest of a pair, placed against a block that does nothing when clicked
    private boolean pairsChest(Placement placement, BuilderCalculationContext bcc) {
        BlockPos at = placement.placeAgainst.relative(placement.side);
        BlockState desired = bcc.getSchematic(at.getX(), at.getY(), at.getZ(), bcc.bsi.get0(at.getX(), at.getY(), at.getZ()));
        return desired != null && desired.getBlock() instanceof ChestBlock && desired.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                && quiet(ctx.world().getBlockState(placement.placeAgainst));
    }

    // hb: CHEST_PAIRS: clicked, nothing happens: rock, and logs, planks and glass (a chest went onto the
    // log under it sneaking, and came out single beside its other half)
    private static boolean quiet(BlockState state) {
        return !state.hasBlockEntity() && (plain(state) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.IMPERMEABLE));
    }

    // hb: CHEST_PAIRS: placed from the look and crouch as they are, the chest pairs as wanted, or its other
    // half isn't in yet (it goes in single, and pairs when that one does)
    private boolean chestPairs(BlockState desired, BlockHitResult hit, int slot, BlockPos at) {
        BlockState other = ctx.world().getBlockState(at.relative(ChestBlock.getConnectedDirection(desired)));
        if (!(other.getBlock() instanceof ChestBlock)) {
            return true;
        }
        ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(slot);
        BlockState would = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(
                new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, hit) {}));
        return would != null && would.getValue(ChestBlock.TYPE) == desired.getValue(ChestBlock.TYPE);
    }

    private long whyNotAt; // hb: when whyNotPlaced last said

    /**
     * hb: at a FACING_SPOTS spot for a block, the player's own body over the edge of the cell it goes in
     * (a chest, from the side, half a body into its cell): a step away from the cell, as a player
     * steps back to place. Nothing else was in the way, and nothing got placed for half a minute.
     * True if it's stepping.
     */
    private boolean stepOffCell() {
        BetterBlockPos feet = ctx.playerFeet();
        long here = BetterBlockPos.longHash(feet.x, feet.y, feet.z);
        AABB body = ctx.player().getBoundingBox();
        for (Map.Entry<BlockPos, PlaceSpots> e : placeSpots.entrySet()) {
            BlockPos pos = e.getKey();
            BlockState toPlace = e.getValue().desired();
            if (!e.getValue().spots().contains(here) || !(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)
                    || toPlace.getCollisionShape(ctx.world(), pos).move(pos.getX(), pos.getY(), pos.getZ()).toAabbs().stream().noneMatch(body::intersects)) {
                continue;
            }
            double ax = body.getCenter().x - (pos.getX() + 0.5);
            double az = body.getCenter().z - (pos.getZ() + 0.5);
            double yaw = Math.toRadians(ctx.player().getYRot());
            double forward = -Math.sin(yaw) * ax + Math.cos(yaw) * az;
            double left = Math.cos(yaw) * ax + Math.sin(yaw) * az;
            Input key = Math.abs(forward) >= Math.abs(left) ? (forward > 0 ? Input.MOVE_FORWARD : Input.MOVE_BACK)
                    : (left > 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT);
            baritone.getInputOverrideHandler().setInputForceState(key, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true); // not off the spot's edge
            return true;
        }
        return false;
    }

    /**
     * hb: standing at a FACING_SPOTS spot for a block and placing nothing: why, face by face, every
     * 5 s (debug). A repeater went unplaced from a spot its simulation passed, and nothing said why.
     */
    private void whyNotPlaced(BuilderCalculationContext bcc) {
        long now = ctx.world().getGameTime();
        if (now - whyNotAt < 100 || !Baritone.settings().chatDebug.value) {
            return;
        }
        BetterBlockPos feet = ctx.playerFeet();
        long here = BetterBlockPos.longHash(feet.x, feet.y, feet.z);
        for (Map.Entry<BlockPos, PlaceSpots> e : placeSpots.entrySet()) {
            BlockPos pos = e.getKey();
            if (!e.getValue().spots().contains(here) || !(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {
                continue;
            }
            BlockState toPlace = e.getValue().desired();
            StringBuilder why = new StringBuilder();
            for (Direction against : Direction.values()) {
                BlockPos at = pos.relative(against);
                BlockState state = ctx.world().getBlockState(at);
                why.append(' ').append(against.getSerializedName()).append(':');
                if (MovementHelper.isReplaceable(at.getX(), at.getY(), at.getZ(), state, bcc.bsi)) {
                    why.append("nothing");
                    continue;
                }
                VoxelShape shape = state.getShape(ctx.world(), at);
                if (shape.isEmpty()) {
                    why.append("no shape");
                    continue;
                }
                AABB aabb = shape.bounds();
                String best = "no point";
                for (Vec3 m : aabbSideMultipliers(against)) {
                    Vec3 point = new Vec3(at.getX() + aabb.minX * m.x + aabb.maxX * (1 - m.x),
                            at.getY() + aabb.minY * m.y + aabb.maxY * (1 - m.y), at.getZ() + aabb.minZ * m.z + aabb.maxZ * (1 - m.z));
                    Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), point, ctx.playerRotations());
                    HitResult r = RayTraceUtils.rayTraceTowards(ctx.player(), rot, ctx.playerController().getBlockReachDistance(), true);
                    if (!(r instanceof BlockHitResult hit) || r.getType() != HitResult.Type.BLOCK) {
                        best = "ray hits nothing";
                    } else if (!hit.getBlockPos().equals(at) || hit.getDirection() != against.getOpposite()) {
                        best = "ray hits " + hit.getBlockPos() + " " + hit.getDirection();
                    } else if (!placesAround(toPlace, rot, at, against.getOpposite())) {
                        best = "not around";
                    } else if (hasAnyItemThatWouldPlace(toPlace, hit, rot).isEmpty()) {
                        best = "no hotbar item places it so";
                    } else {
                        best = "ok";
                        break;
                    }
                }
                why.append(best);
            }
            logDebug("At a spot for " + toPlace + " at " + pos + " and not placing:" + why
                    + (toPlace.canSurvive(ctx.world(), pos) ? "" : "; can't survive")
                    + (placementPlausible(pos, toPlace) ? "" : "; something in the way"));
            whyNotAt = now;
            return;
        }
    }

    // hb: how far off the planned look a sink's camera may land and still be on the face, degrees
    private static final float AROUND_DEG = 1.0f;

    /**
     * hb: with the look a little off {@code rot} every way, the face is still hit and the block still
     * placed as wanted. A camera lands near the planned look, not on it: it never got onto a face seen
     * only along a grazing line past another block, and a piston planned at 44 degrees up came out
     * facing down from 46, so the builder held off clicking until the watchdog gave up.
     */
    private boolean placesAround(BlockState toPlace, Rotation rot, BlockPos against, Direction face) {
        for (float[] d : new float[][]{{AROUND_DEG, 0}, {-AROUND_DEG, 0}, {0, AROUND_DEG}, {0, -AROUND_DEG}}) {
            Rotation off = new Rotation(rot.getYaw() + d[0], Math.max(-90, Math.min(90, rot.getPitch() + d[1])));
            HitResult r = RayTraceUtils.rayTraceTowards(ctx.player(), off, ctx.playerController().getBlockReachDistance(), true);
            if (!(r instanceof BlockHitResult hit) || r.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(against) || hit.getDirection() != face
                    || hasAnyItemThatWouldPlace(toPlace, hit, off).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            // the state depends on the facing of the player sometimes
            ctx.player().setYRot(rot.getYaw());
            ctx.player().setXRot(rot.getPitch());
            BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(
                    ctx.world(),
                    ctx.player(),
                    InteractionHand.MAIN_HAND,
                    stack,
                    (BlockHitResult) result
            ) {}); // that {} gives us access to a protected constructor lmfao
            BlockState wouldBePlaced = stateFor((BlockItem) stack.getItem(), meme); // hb: WALL_VARIANT
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
            if (wouldBePlaced == null) {
                continue;
            }
            if (!meme.canPlace()) {
                continue;
            }
            if (valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        noteIdleSpot(bcc); // hb
        updateProps(bcc); // hb
        if (ctx.player().onClimbable() && !ctx.player().onGround() && !baritone.getPathingBehavior().isPathing()) {
            // hb: on a ladder, the path done: holding on (sneak), as a player does to break or place from
            // it. Let go, the player slid down off the spot it had climbed to, climbed again, slid again
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        // hb: from a spot where nothing got done for a while, neither break nor place: walk elsewhere
        boolean idleHere = idleSpots.contains(ctx.playerFeet());
        // hb: and from a ladder too (holding sneak on one, a player stays put): a shaft's next ladder
        // is placed from the last
        boolean standing = ctx.player().onGround() || ctx.player().onClimbable();
        Optional<Pair<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && !idleHere && isSafeToCancel && standing) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().second();
            BetterBlockPos pos = toBreak.get().first();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            // hb: with an intent sink the crouch is the sink's own (a player's sneaking habit), not one
            // this process started to place, and holding it would hand it back to the sink a tick late
            if (ctx.player().isCrouching() && baritone.getIntentSink() == null) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (toPlace.isEmpty()) {
            whyNotPlaced(bcc); // hb
            if (!idleHere && isSafeToCancel && stepOffCell()) { // hb
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        if (toPlace.isPresent() && !idleHere && isSafeToCancel && standing && ticks <= 0) {
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            // hb: CHEST_PAIRS: a chest clicked onto rock without sneaking, as a player places one, so it
            // pairs with the one beside it (sneaking, every chest of the farm went in single)
            if (!pairsChest(toPlace.get(), bcc)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (((ctx.isLookingAt(toPlace.get().placeAgainst) && ((BlockHitResult) ctx.objectMouseOver()).getDirection().equals(toPlace.get().side)) || ctx.playerRotations().isReallyCloseTo(rot))
                    && (baritone.getIntentSink() == null || placesAsWanted(toPlace.get(), bcc))) { // hb
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // hb: SET_DELAY: a repeater in, its delay not the schematic's: clicked (not sneaking, a tool in hand)
        // till it is, as a player sets one. The builder took any delay for right, and left them all at 1
        Optional<Pair<BetterBlockPos, Rotation>> toSet = toSetNearPlayer();
        if (toSet.isPresent() && !idleHere && isSafeToCancel && standing) {
            baritone.getLookBehavior().updateTarget(toSet.get().second(), true);
            for (int i = 0; i < 9; i++) {
                if (!(ctx.player().getInventory().getNonEquipmentItems().get(i).getItem() instanceof BlockItem)) {
                    ctx.player().getInventory().setSelectedSlot(i); // sneaking with a block, it'd go on top
                    break;
                }
            }
            if (ctx.isLookingAt(toSet.get().first()) && !ctx.player().isShiftKeyDown()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            if (placeChecks++ % 20 == 0) {
                logDebug("Setting the repeater at " + toSet.get().first() + ", looking at it " + ctx.isLookingAt(toSet.get().first())
                        + ", sneaking " + ctx.player().isShiftKeyDown()); // hb: debug
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (couldPlace(approxPlaceable.get(i), desired)) { // hb
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (couldPlace(approxPlaceable.get(i), desired)) { // hb
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        if (!idleSpots.isEmpty()) { // hb
            goal = new GoalExcept(goal, Set.copyOf(idleSpots));
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    /**
     * hb: the player has stood at one spot for IDLE_SPOT_TICKS with nothing built or broken. The goal
     * can hold the player where nothing can be done (the block isn't in sight from there, or can't be
     * placed facing the way it should), and being in the goal, the player never moves again; or a
     * placement the builder sees from there never gets clicked. The spot is left out of the goal, and
     * nothing is broken or placed from it, until something gets done.
     */
    private void noteIdleSpot(BuilderCalculationContext bcc) {
        BetterBlockPos feet = ctx.playerFeet();
        // what's still to do, and how much of it is open: a block broken where another is to go
        // leaves the first number as it was
        long open = incorrectPositions.stream().filter(p -> bcc.bsi.get0(p).getBlock() instanceof AirBlock).count();
        long toDo = incorrectPositions.size() * 100_000L + open;
        if (toDo != lastToDo) {
            lastToDo = toDo;
            idleSpots.clear();
            idleTicks = 0;
        } else if (!feet.equals(idleAt)) {
            idleTicks = 0;
        } else if (++idleTicks >= IDLE_SPOT_TICKS) {
            idleSpots.add(feet);
            idleTicks = 0;
            // hb: FACING_SPOTS: a spot for a block, its cell clear, and nothing placed from it: not a spot
            // for it (its simulation passed, the builder's own check from where the player stood didn't)
            long here = BetterBlockPos.longHash(feet.x, feet.y, feet.z);
            placeSpots.entrySet().removeIf(e -> {
                if (!e.getValue().spots().contains(here) || !(ctx.world().getBlockState(e.getKey()).getBlock() instanceof AirBlock)) {
                    return false;
                }
                badSpots.computeIfAbsent(e.getKey().immutable(), k -> new HashSet<>()).add(here);
                logDebug("Not a spot for " + e.getKey() + ": " + feet);
                return true;
            });
            logDebug("Nothing done from " + feet + " for a while, standing elsewhere");
        }
        idleAt = feet;
    }

    /** hb: a goal without some standing spots in it (noteIdleSpot). */
    public record GoalExcept(Goal goal, Set<BetterBlockPos> except) implements Goal {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return goal.isInGoal(x, y, z) && !except.contains(new BetterBlockPos(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return goal.heuristic(x, y, z);
        }
    }

    // hb: SET_DELAY: repeaters in whose delay isn't the schematic's yet
    private final Set<BetterBlockPos> toSet = new HashSet<>();

    // hb: SET_DELAY: in, but set wrong (a repeater's delay is clicked to, not placed)
    private static boolean setWrong(BlockState current, BlockState desired) {
        return desired != null && current.getBlock() instanceof RepeaterBlock && desired.getBlock() instanceof RepeaterBlock
                && !current.getValue(RepeaterBlock.DELAY).equals(desired.getValue(RepeaterBlock.DELAY));
    }

    private void noteSet(BetterBlockPos pos, BlockState current, BlockState desired) {
        if (setWrong(current, desired)) {
            if (!toSet.contains(pos)) {
                logDebug("Repeater at " + pos + " to set: delay " + current.getValue(RepeaterBlock.DELAY) + ", wants " + desired.getValue(RepeaterBlock.DELAY));
            }
            toSet.add(pos);
        } else {
            toSet.remove(pos);
        }
    }

    // hb: SET_DELAY: one in reach and in sight
    private Optional<Pair<BetterBlockPos, Rotation>> toSetNearPlayer() {
        for (BetterBlockPos pos : toSet) {
            if (!(ctx.world().getBlockState(pos).getBlock() instanceof RepeaterBlock)) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
            if (rot.isPresent()) {
                return Optional.of(new Pair<>(pos, rot.get()));
            }
        }
        return Optional.empty();
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        toSet.removeIf(p -> !setWrong(bcc.bsi.get0(p), bcc.getSchematic(p.x, p.y, p.z, bcc.bsi.get0(p)))); // hb
        return !incorrectPositions.isEmpty() || !toSet.isEmpty(); // hb: SET_DELAY: a layer's done once they're set
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        noteSet(pos, bcc.bsi.get0(x, y, z), desired); // hb
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        BlockState wanted = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                        noteSet(new BetterBlockPos(blockX, blockY, blockZ), current, wanted); // hb
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), wanted, false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (approxPlaceable.stream().anyMatch(have -> couldPlace(have, desired))) { // hb
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        placeable.forEach(pos -> {
            if (propPending(pos)) {
                return; // hb: PROPS: placed once its prop is in
            }
            BlockState wanted = bcc.getSchematic(pos.x, pos.y, pos.z, bcc.bsi.get0(pos));
            if (wanted != null && !wanted.canSurvive(ctx.world(), pos)) {
                // hb: not yet: what it hangs on isn't there (a wall torch, the rock behind it dug out): the
                // player stood with their head where that rock goes, to place the torch, for good
                return;
            }
            if (!placeable.contains(pos.below()) && !placeable.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));
        toSet.forEach(pos -> toPlace.add(new GoalGetToBlock(pos))); // hb: SET_DELAY

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current); // hb: FACING_SPOTS
        if (desired != null && facesAsPlaced(desired)) {
            Set<Long> spots = placeSpots(pos, desired, allowSameLevel, bcc).spots();
            if (!spots.isEmpty()) {
                return new GoalPlaceFrom(pos, spots);
            }
        }
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, pos.relative(facing)) && placementPlausible(pos, bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current))) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        return new GoalPlace(pos);
    }

    // hb: FACING_SPOTS, see placeSpots
    private final Map<BlockPos, PlaceSpots> placeSpots = new HashMap<>();

    // hb: FACING_SPOTS: spots stood at for a block, it not placed (noteIdleSpot): left out from then on
    private final Map<BlockPos, Set<Long>> badSpots = new HashMap<>();

    /**
     * hb: FACING_SPOTS and PROPS. {@code spots}: where to stand to place the block as wanted, the world
     * as it is. {@code lasting}: placeable as wanted against blocks the schematic keeps ({@code keeps},
     * a bit per Direction), not only against ones it has go (rock dug out, air). {@code prop}: if not,
     * the block around it, one the schematic has go, that with a block there would make it placeable.
     */
    private record PlaceSpots(BlockState desired, List<BlockState> around, int keeps, Set<Long> spots, boolean lasting, BlockPos prop,
                              List<BlockPos> clear, long until) {}

    // hb: FACING_SPOTS: spots two out are looked through other blocks, so they go stale as those change
    private static final int FAR_SPOT_TICKS = 100;

    // hb: how far the look may be off and the block still come out as wanted, degrees
    private static final float SPOT_SLACK_DEG = 2.0f;

    // hb: how far off the middle of a spot the player may stand and still place from it, blocks
    private static final double EYE_OFF = 0.15;

    /**
     * hb: FACING_SPOTS. Where to stand (feet, next to {@code pos} as GoalGetToBlock has it) to place a
     * block whose state comes from the look or the face clicked (a jack o'lantern facing north is
     * placed from its north side): each spot where some face of a block around {@code pos}, seen
     * from the eye there, places it as wanted. GoalAdjacent had the player stand anywhere next to
     * it but in the block clicked against, so underground, with the rock to the north the only face
     * to click from the right side, it stood east, placed nothing, and was sent elsewhere for good.
     * A spot in the rock is dug out to stand in. None if nothing in the inventory places it.
     */
    private PlaceSpots placeSpots(BlockPos pos, BlockState desired, boolean allowSameLevel, BuilderCalculationContext bcc) {
        List<BlockState> around = new ArrayList<>(6);
        int keeps = 0;
        for (Direction d : Direction.values()) {
            BlockPos at = pos.relative(d);
            BlockState state = ctx.world().getBlockState(at);
            around.add(state);
            BlockState sch = bcc.schematicAt(at.getX(), at.getY(), at.getZ(), state);
            if (sch != null && !(sch.getBlock() instanceof AirBlock)) {
                keeps |= 1 << d.ordinal();
            }
        }
        PlaceSpots cached = placeSpots.get(pos);
        long now = ctx.world().getGameTime();
        if (cached != null && cached.desired() == desired && cached.keeps() == keeps && cached.around().equals(around) && now < cached.until()) {
            return cached;
        }
        ItemStack stack = null;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (s.getItem() instanceof BlockItem item && item.getBlock().asItem() == desired.getBlock().asItem()) {
                stack = s;
                break;
            }
        }
        Set<Long> spots = new HashSet<>();
        boolean lasting = false;
        BlockPos prop = null;
        List<BlockPos> clear = List.of();
        long until = Long.MAX_VALUE;
        if (stack != null) {
            double eyeHeight = RayTraceUtils.inferSneakingEyePosition(ctx.player()).y - ctx.player().getY();
            double reach = ctx.playerController().getBlockReachDistance();
            List<BlockPos> feet = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 0; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) == 1 && (dy == 0 || allowSameLevel)) {
                            feet.add(pos.offset(dx, dy, dz)); // GoalGetToBlock's spots, but for the column of pos itself
                        }
                    }
                }
            }
            int keep = keeps;
            // not where a finished block of the build is (a path broke a hopper to stand in its place),
            // nor over one that can't be stood on (a hopper: a player sinks into its bowl)
            Set<Long> bad = badSpots.getOrDefault(pos, Set.of());
            feet.removeIf(f -> finished(f, bcc) || finished(f.above(), bcc) || !standable(f, bcc)
                    || bad.contains(BetterBlockPos.longHash(f.getX(), f.getY(), f.getZ())));
            // each spot, and the cells there where a block of the build is yet to go (a creaking heart
            // where the player stands to place a piston): a spot with none, or else the one with fewest
            Map<BlockPos, List<BlockPos>> needs = new LinkedHashMap<>();
            for (BlockPos f : feet) {
                Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + eyeHeight, f.getZ() + 0.5);
                if (placesFrom(stack, desired, pos, f, eye, reach, d -> true, null, null, bcc)) {
                    needs.put(f, yetToGo(bcc, f, f.above()));
                    lasting |= placesFrom(stack, desired, pos, f, eye, reach, d -> (keep >> d.ordinal() & 1) != 0, null, null, bcc);
                }
            }
            if (needs.isEmpty()) {
                // none next to it: two out, looking past the blocks between (kept clear of rock), as a
                // player places a piston facing a creaking heart from the heart's place, before the heart
                until = now + FAR_SPOT_TICKS;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos f = pos.offset(dx, dy, dz);
                            if ((dx == 0 && dz == 0) || (Math.abs(dx) + Math.abs(dz) == 1 && dy <= 0)
                                    || finished(f, bcc) || finished(f.above(), bcc) || !standable(f, bcc)
                                    || bad.contains(BetterBlockPos.longHash(f.getX(), f.getY(), f.getZ()))) {
                                continue;
                            }
                            Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + eyeHeight, f.getZ() + 0.5);
                            List<BlockPos> between = new ArrayList<>();
                            if (placesFrom(stack, desired, pos, f, eye, reach, d -> true, null, between, bcc)) {
                                between.addAll(yetToGo(bcc, f, f.above()));
                                needs.put(f, between);
                                List<BlockPos> unused = new ArrayList<>();
                                lasting |= placesFrom(stack, desired, pos, f, eye, reach, d -> (keep >> d.ordinal() & 1) != 0, null, unused, bcc);
                            }
                        }
                    }
                }
            }
            needs.forEach((f, n) -> {
                if (n.isEmpty()) {
                    spots.add(BetterBlockPos.longHash(f.getX(), f.getY(), f.getZ()));
                }
            });
            if (spots.isEmpty() && !needs.isEmpty()) {
                // fewest cells to keep clear, then the most head-on (a piston facing a heart from the
                // heart's place, not from a corner past a log)
                // fewest blocks of the build kept waiting (rock to dig out of the way is cheap: counted first,
                // the rock over a creaking heart sent a piston to a corner past a log), then level with it,
                // not on top of a heart, then the most head-on, then the fewest cells in all
                Map.Entry<BlockPos, List<BlockPos>> best = Collections.min(needs.entrySet(), Comparator
                        .<Map.Entry<BlockPos, List<BlockPos>>>comparingInt(e -> yetToGo(bcc, e.getValue().toArray(new BlockPos[0])).size())
                        .thenComparingInt(e -> Math.abs(e.getKey().getY() - pos.getY()))
                        .thenComparingInt(e -> Math.min(Math.abs(e.getKey().getX() - pos.getX()), Math.abs(e.getKey().getZ() - pos.getZ())))
                        .thenComparingInt(e -> e.getValue().size()));
                spots.add(BetterBlockPos.longHash(best.getKey().getX(), best.getKey().getY(), best.getKey().getZ()));
                clear = List.copyOf(best.getValue());
            }
            // PROPS: none for a block that hangs on what's around it (a torch, a repeater): it'd drop
            // with the prop broken
            if (!lasting && desired.canSurvive(ctx.world(), pos) && Block.isShapeFullBlock(desired.getCollisionShape(ctx.world(), pos))) {
                props:
                for (Direction d : Direction.values()) {
                    if ((keeps >> d.ordinal() & 1) != 0) {
                        continue;
                    }
                    BlockPos at = pos.relative(d);
                    for (BlockPos f : feet) {
                        Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + eyeHeight, f.getZ() + 0.5);
                        if (placesFrom(stack, desired, pos, f, eye, reach, e -> (keep >> e.ordinal() & 1) != 0, at, null, bcc)) {
                            prop = at.immutable();
                            break props;
                        }
                    }
                }
            }
        }
        if (placeSpots.size() > 4096) {
            placeSpots.clear();
        }
        PlaceSpots result = new PlaceSpots(desired, around, keeps, spots, lasting, prop, clear, until);
        placeSpots.put(pos.immutable(), result);
        return result;
    }

    /**
     * hb: the side a block hangs from, the block there holding it up: a button's or lever's, a wall
     * torch's, a ladder's, a wall sign's or banner's; the bottom for a repeater, comparator, wire,
     * standing torch, carpet, pressure plate or rail. Null if it stands on its own.
     */
    private static Direction hangsFrom(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
            return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
                case FLOOR -> Direction.DOWN;
                case CEILING -> Direction.UP;
                case WALL -> state.getValue(HorizontalDirectionalBlock.FACING).getOpposite();
            };
        }
        if (block instanceof WallTorchBlock || block instanceof RedstoneWallTorchBlock || block instanceof LadderBlock
                || block instanceof WallSignBlock || block instanceof WallBannerBlock) {
            return state.getValue(HorizontalDirectionalBlock.FACING).getOpposite();
        }
        if (block instanceof DiodeBlock || block instanceof RedStoneWireBlock || block instanceof BaseTorchBlock
                || block instanceof CarpetBlock || block instanceof BasePressurePlateBlock || block instanceof BaseRailBlock) {
            return Direction.DOWN;
        }
        return null;
    }

    // hb: FACING_SPOTS: the cells of these where a block of the build is yet to go (not rock)
    private List<BlockPos> yetToGo(BuilderCalculationContext bcc, BlockPos... cells) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos c : cells) {
            BlockState there = ctx.world().getBlockState(c);
            BlockState sch = bcc.schematicAt(c.getX(), c.getY(), c.getZ(), there);
            if (sch != null && !(sch.getBlock() instanceof AirBlock) && !rock(sch) && !valid(there, sch, false)) {
                result.add(c.immutable());
            }
        }
        return result;
    }

    // hb: the build's filler, a block the schematic lets be any of its substitutes (or one of those:
    // the builder's schematic hands back the substitute it has)
    private static boolean rock(BlockState sch) {
        Map<Block, List<Block>> subs = Baritone.settings().buildSubstitutes.value;
        return subs.containsKey(sch.getBlock()) || subs.values().stream().anyMatch(l -> l.contains(sch.getBlock()));
    }

    // hb: FACING_SPOTS: to stand at, on something walkable or to be (not a finished hopper)
    private boolean standable(BlockPos feet, BuilderCalculationContext bcc) {
        BlockPos floor = feet.below();
        return MovementHelper.canWalkOn(baritone.bsi, floor.getX(), floor.getY(), floor.getZ(), ctx.world().getBlockState(floor))
                || !finished(floor, bcc);
    }

    // hb: FACING_SPOTS: a block of the build there, in and right, and not plain rock
    private boolean finished(BlockPos at, BuilderCalculationContext bcc) {
        BlockState there = ctx.world().getBlockState(at);
        BlockState sch = bcc.schematicAt(at.getX(), at.getY(), at.getZ(), there);
        return sch != null && !(sch.getBlock() instanceof AirBlock) && !(there.getBlock() instanceof AirBlock)
                && valid(there, sch, false) && !plain(there);
    }

    /**
     * hb: FACING_SPOTS: from the eye at a spot (feet, and the head over them, dug out if need be),
     * clicking a face of a block around {@code pos} on a side {@code usable} has, or of a block put
     * at {@code solid}
     */
    private boolean placesFrom(ItemStack stack, BlockState desired, BlockPos pos, BlockPos feet, Vec3 eye, double reach,
                               java.util.function.Predicate<Direction> usable, BlockPos solid, List<BlockPos> between, BuilderCalculationContext bcc) {
        List<BlockPos> fewest = null;
        for (Direction against : Direction.values()) {
            BlockPos at = pos.relative(against);
            if (at.equals(feet) || at.equals(feet.above())) {
                continue;
            }
            boolean put = at.equals(solid);
            if (!put && !usable.test(against)) {
                continue;
            }
            BlockState state = put ? Blocks.STONE.defaultBlockState() : ctx.world().getBlockState(at);
            if (!put && MovementHelper.isReplaceable(at.getX(), at.getY(), at.getZ(), state, baritone.bsi)) {
                continue;
            }
            VoxelShape shape = put ? Shapes.block() : state.getShape(ctx.world(), at);
            if (shape.isEmpty()) {
                continue;
            }
            Direction face = against.getOpposite();
            AABB aabb = shape.bounds();
            for (Vec3 m : aabbSideMultipliers(against)) {
                Vec3 point = new Vec3(
                        at.getX() + aabb.minX * m.x + aabb.maxX * (1 - m.x),
                        at.getY() + aabb.minY * m.y + aabb.maxY * (1 - m.y),
                        at.getZ() + aabb.minZ * m.z + aabb.maxZ * (1 - m.z));
                Vec3 toEye = eye.subtract(point);
                if (toEye.dot(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ())) <= 0.01 || toEye.length() > reach) {
                    continue; // the face is seen from behind, or out of reach
                }
                boolean all = true;
                // from the middle of the spot and from a little off it every way (a player stands where
                // they stopped, not at the middle: a spot on a creaking heart passed from its middle, and
                // from where the player stood the piston's every look grazed a face or tipped it upward)
                eyes:
                for (Vec3 e : new Vec3[]{eye, eye.add(EYE_OFF, 0, 0), eye.add(-EYE_OFF, 0, 0), eye.add(0, 0, EYE_OFF), eye.add(0, 0, -EYE_OFF)}) {
                    if (e.subtract(point).dot(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ())) <= 0.01) {
                        all = false;
                        break;
                    }
                    Rotation rot = RotationUtils.calcRotationFromVec3d(e, point, ctx.playerRotations());
                    for (float[] d : new float[][]{{0, 0}, {SPOT_SLACK_DEG, 0}, {-SPOT_SLACK_DEG, 0}, {0, SPOT_SLACK_DEG}, {0, -SPOT_SLACK_DEG}}) {
                        Rotation off = new Rotation(rot.getYaw() + d[0], Math.max(-90, Math.min(90, rot.getPitch() + d[1])));
                        // the look a little off still on the face (a point at its edge passed here, and the
                        // builder's own check, from the player, then never let it click)
                        Vec3 on = onFace(e, off, at, aabb, face);
                        if (on == null || !wouldPlace(stack, desired, new BlockHitResult(on, face, at, false), off)) {
                            all = false;
                            break eyes;
                        }
                    }
                }
                if (all) {
                    if (between == null) {
                        return true;
                    }
                    List<BlockPos> inWay = inTheWay(eye, point, pos, at, feet, bcc);
                    if (inWay != null && (fewest == null || inWay.size() < fewest.size())) {
                        fewest = inWay;
                    }
                }
            }
        }
        if (fewest != null) {
            between.addAll(fewest);
            return true;
        }
        return false;
    }

    // hb: FACING_SPOTS: where a look from the eye meets the face (of the block at {@code at}, its shape's
    // bounds {@code aabb}), or null if it misses it
    private static Vec3 onFace(Vec3 eye, Rotation look, BlockPos at, AABB aabb, Direction face) {
        Vec3 d = RotationUtils.calcLookDirectionFromRotation(look);
        AABB box = aabb.move(at);
        double plane = switch (face) {
            case EAST -> box.maxX;
            case WEST -> box.minX;
            case UP -> box.maxY;
            case DOWN -> box.minY;
            case SOUTH -> box.maxZ;
            case NORTH -> box.minZ;
        };
        double along = face.getAxis().choose(d.x, d.y, d.z);
        if (Math.abs(along) < 1e-6) {
            return null;
        }
        double t = (plane - face.getAxis().choose(eye.x, eye.y, eye.z)) / along;
        if (t <= 0) {
            return null;
        }
        Vec3 p = eye.add(d.scale(t));
        double e = 0.02;
        boolean in = switch (face.getAxis()) {
            case X -> p.y > box.minY + e && p.y < box.maxY - e && p.z > box.minZ + e && p.z < box.maxZ - e;
            case Y -> p.x > box.minX + e && p.x < box.maxX - e && p.z > box.minZ + e && p.z < box.maxZ - e;
            case Z -> p.x > box.minX + e && p.x < box.maxX - e && p.y > box.minY + e && p.y < box.maxY - e;
        };
        return in ? p : null;
    }

    /**
     * hb: FACING_SPOTS: the blocks on the line from the eye to the point clicked (but for the cell
     * placed in, the one clicked, and the player's own), to be cleared; null if one is a block of the
     * build, in and right, that can't be
     */
    private List<BlockPos> inTheWay(Vec3 eye, Vec3 point, BlockPos pos, BlockPos at, BlockPos feet, BuilderCalculationContext bcc) {
        // every cell the line passes through, as the game's own ray does (points along it missed a
        // corner the line clipped), and the block there in the way only if the line meets its outline
        // (the shape a look stops at: a look over a repeater passes)
        List<BlockPos> result = new ArrayList<>();
        boolean blocked = Boolean.TRUE.equals(net.minecraft.world.level.BlockGetter.traverseBlocks(eye, point, null, (unused, c) -> {
            if (c.equals(pos) || c.equals(at) || c.equals(feet) || c.equals(feet.above())) {
                return null;
            }
            BlockState state = ctx.world().getBlockState(c);
            VoxelShape shape = state.getShape(ctx.world(), c);
            if (shape.isEmpty() || shape.clip(eye, point, c) == null) {
                return null;
            }
            if (finished(c, bcc)) {
                return Boolean.TRUE;
            }
            BlockState sch = bcc.schematicAt(c.getX(), c.getY(), c.getZ(), state);
            if (sch != null && sch.getBlock() instanceof AirBlock) {
                // to be dug out anyway: no cell to keep clear (counted as one, the gap between a piston
                // and a creaking heart put the heart's place behind a corner past a log, and the heart
                // went in first)
                return null;
            }
            result.add(c.immutable());
            return null;
        }, unused -> Boolean.FALSE));
        return blocked ? null : result;
    }

    // hb: FACING_SPOTS: one stack, as hasAnyItemThatWouldPlace does for each hotbar slot
    private boolean wouldPlace(ItemStack stack, BlockState desired, BlockHitResult hit, Rotation rot) {
        float originalYaw = ctx.player().getYRot();
        float originalPitch = ctx.player().getXRot();
        ctx.player().setYRot(rot.getYaw());
        ctx.player().setXRot(rot.getPitch());
        BlockPlaceContext meme = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, hit) {});
        BlockState wouldBePlaced = stateFor((BlockItem) stack.getItem(), meme);
        ctx.player().setYRot(originalYaw);
        ctx.player().setXRot(originalPitch);
        // not whether it can go in now: the cell may be rock to dig out first
        return wouldBePlaced != null && valid(wouldBePlaced, desired, true);
    }

    /**
     * hb: PROPS. A block put in where the schematic has air (or a block of the rock left there) to
     * place another against, broken once that one is in. A pale oak log lying north-south between
     * two air gaps is set by clicking a north or south face, and there was none: the rock either
     * side was dug out as the air it should be, and the next log along wasn't in either, so it went
     * unplaced (or placed as it came, then broken, and round again). A player leaves the rock, or puts
     * a block in, places the log, and breaks it. Keyed by position (BetterBlockPos.longHash): the
     * builder's schematic (BuilderCalculationContext.getSchematic, read by the path threads too) has
     * the block there that's there, if solid, else {@code block}.
     */
    private record Prop(BlockPos at, BlockPos forPos, BlockState block, boolean clear, long since, boolean lid) {}

    // hb: PROPS: a cell kept clear this long and the block it's for not in: let it go, for good. At two
    // minutes, with the rest of a layer to do, a log cell kept clear for a piston to look past let go
    // before the piston's turn came, the log went in, and the piston had no way left; one given up on
    // from the spot itself goes (updateProps)
    private static final int CLEAR_GIVE_UP_TICKS = 12000;

    // hb: PROPS: blocks given up keeping cells clear for, and when: for a while only (a piston gave up
    // after one spot, the creaking hearts went in, and it had no way left the rest of the attempt)
    private final Map<BlockPos, Long> clearGivenUp = new HashMap<>();

    private static final int GIVEN_UP_TICKS = 600;

    private final Map<Long, Prop> props = new ConcurrentHashMap<>();

    private void updateProps(BuilderCalculationContext bcc) {
        // done with: the block it was for is in (or something is), or not wanted there now (a layer)
        long now = ctx.world().getGameTime();
        props.values().removeIf(p -> {
            BlockState there = ctx.world().getBlockState(p.forPos());
            BlockState want = bcc.schematicAt(p.forPos().getX(), p.forPos().getY(), p.forPos().getZ(), there);
            // a lid is done with once the prop under it is broken
            boolean done = p.lid() ? there.getBlock() instanceof AirBlock
                    : (!(there.getBlock() instanceof AirBlock) && !plain(there)) || want == null || !facesAsPlaced(want);
            if (!done && p.clear() && !p.lid() && (now - p.since() > CLEAR_GIVE_UP_TICKS || triedFromAll(p.forPos()))) {
                done = true;
                clearGivenUp.put(p.forPos(), now);
            }
            if (done) {
                BlockState prop = ctx.world().getBlockState(p.at());
                BlockState sch = bcc.schematicAt(p.at().getX(), p.at().getY(), p.at().getZ(), prop);
                if (sch != null && !valid(prop, sch, false)) {
                    incorrectPositions.add(new BetterBlockPos(p.at())); // for the builder to see it's to go, or to come
                } else {
                    // nothing to do there now (a lid over a layer still to come): off the to-do list, where
                    // it was a block to break the builder never would, until it gave up
                    incorrectPositions.remove(new BetterBlockPos(p.at()));
                }
                logDebug("Done with the prop at " + p.at() + " for " + p.forPos());
            }
            return done;
        });
        List<Prop> added = new ArrayList<>();
        BlockState block = null;
        for (BetterBlockPos pos : incorrectPositions) {
            BlockState here = ctx.world().getBlockState(pos);
            if (!(here.getBlock() instanceof AirBlock)) {
                // to be broken but shut in on every side but the top (a prop between a piston and a
                // creaking heart, walled in by the blocks placed round it since): the two cells over it
                // kept clear, to break it from beside them, from above, not standing on it into a pit
                BlockState sch = bcc.getSchematic(pos.x, pos.y, pos.z, here);
                if (sch != null && sch.getBlock() instanceof AirBlock && sealedIn(pos, bcc)) {
                    for (BlockPos c : new BlockPos[]{pos.above(), pos.above(2)}) {
                        if (!finished(c, bcc) && !props.containsKey(BetterBlockPos.longHash(c.getX(), c.getY(), c.getZ()))
                                && added.stream().noneMatch(p -> p.at().equals(c))) {
                            added.add(new Prop(c.immutable(), pos, Blocks.AIR.defaultBlockState(), true, now, true));
                        }
                    }
                }
            }
            // a block's cells to keep clear are kept from before its own is dug out: the creaking hearts
            // went in, from rock to heart, while the pistons beside them were rock yet
            BlockState there = here;
            boolean dug = there.getBlock() instanceof AirBlock;
            if (!dug && !plain(there)) {
                continue;
            }
            BlockState want = bcc.schematicAt(pos.x, pos.y, pos.z, there);
            if (want == null || !facesAsPlaced(want)) {
                continue;
            }
            Prop held = props.get(BetterBlockPos.longHash(pos.x, pos.y, pos.z));
            if (held != null && held.clear()) {
                continue; // kept clear for another block: its turn comes after
            }
            boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
            PlaceSpots ps = placeSpots(pos, want, allowSameLevel, bcc);
            if (!ps.clear().isEmpty() && now - clearGivenUp.getOrDefault(pos, Long.MIN_VALUE / 2) > GIVEN_UP_TICKS) {
                // not a cell another block is keeping cells clear for itself from (a repeater and the
                // observer behind it each placed from the other's cell, each kept the other's clear):
                // this one waits its turn
                // (only that: a creaking heart keeping a log's cell clear for itself is no reason for the
                // piston on its other side not to keep the heart's cell clear)
                if (ps.clear().stream().anyMatch(c -> Stream.concat(props.values().stream(), added.stream())
                        .anyMatch(p -> p.clear() && !p.lid() && p.forPos().equals(c) && p.at().equals(pos)))) {
                    continue;
                }
                // every tick, the ones it hasn't (another block's let go: the piston on a creaking heart's
                // other side, once the first was in, took the heart's cell, before the heart did)
                for (BlockPos c : ps.clear()) {
                    if (!props.containsKey(BetterBlockPos.longHash(c.getX(), c.getY(), c.getZ())) && added.stream().noneMatch(p -> p.at().equals(c))) {
                        added.add(new Prop(c, pos, Blocks.AIR.defaultBlockState(), true, now, false));
                    }
                }
                continue;
            }
            if (props.values().stream().anyMatch(p -> p.forPos().equals(pos))) {
                continue; // one prop a block
            }
            if (!dug || ps.lasting() || ps.prop() == null || props.containsKey(BetterBlockPos.longHash(ps.prop().getX(), ps.prop().getY(), ps.prop().getZ()))
                    || added.stream().anyMatch(p -> p.at().equals(ps.prop()))) {
                continue;
            }
            if (block == null && (block = propBlock()) == null) {
                break; // nothing to put in
            }
            added.add(new Prop(ps.prop(), pos, block, false, now, false));
        }
        for (Prop p : added) {
            props.put(BetterBlockPos.longHash(p.at().getX(), p.at().getY(), p.at().getZ()), p);
            logDebug((p.lid() ? "Lid off " : p.clear() ? "Kept clear " : "Prop at ") + p.at() + " for " + p.forPos() + ", " + ctx.world().getBlockState(p.at()));
        }
        // the builder's to-do list, right about the props: one to put in if it isn't there
        for (Prop p : props.values()) {
            BetterBlockPos at = new BetterBlockPos(p.at());
            if (p.clear() ? ctx.world().getBlockState(at).getBlock() instanceof AirBlock : ctx.world().getBlockState(at).isSolidRender()) {
                incorrectPositions.remove(at);
            } else {
                incorrectPositions.add(at);
            }
        }
    }

    // hb: PROPS: the player stood at every spot for the block and placed nothing (noteIdleSpot)
    private boolean triedFromAll(BlockPos pos) {
        PlaceSpots ps = placeSpots.get(pos);
        return ps != null && !ps.spots().isEmpty() && !idleSpots.isEmpty()
                && ps.spots().stream().allMatch(h -> idleSpots.stream().anyMatch(i -> BetterBlockPos.longHash(i.x, i.y, i.z) == h));
    }

    // hb: PROPS: walled in by finished blocks of the build (not rock, which is dug through) on every
    // side but the top
    private boolean sealedIn(BlockPos at, BuilderCalculationContext bcc) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            if (!finished(at.relative(d), bcc)) {
                return false;
            }
        }
        return true;
    }

    // hb: PROPS: a block the placing of {@code pos} waits on
    private boolean propPending(BlockPos pos) {
        for (Prop p : props.values()) {
            if (!p.clear() && p.forPos().equals(pos) && !ctx.world().getBlockState(p.at()).isSolidRender()) {
                return true;
            }
        }
        return false;
    }

    // hb: PROPS: a plain block to put in for one (a throwaway first), never one taken as fine as air
    private BlockState propBlock() {
        BlockState any = null;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!(s.getItem() instanceof BlockItem item) || !plain(item.getBlock().defaultBlockState())
                    || Baritone.settings().okIfAir.value.contains(item.getBlock())) {
                continue;
            }
            if (Baritone.settings().acceptableThrowawayItems.value.contains(s.getItem())) {
                return item.getBlock().defaultBlockState();
            }
            if (any == null) {
                any = item.getBlock().defaultBlockState();
            }
        }
        return any;
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    /**
     * hb: FACING_SPOTS: next to a block whose state comes from the look or the face clicked, at the
     * spots from which it's placed as wanted (placeSpots)
     */
    public static class GoalPlaceFrom extends GoalGetToBlock {

        private final Set<Long> spots;

        public GoalPlaceFrom(BlockPos pos, Set<Long> spots) {
            super(pos);
            this.spots = spots;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return spots.contains(BetterBlockPos.longHash(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates, as GoalAdjacent
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && spots.equals(((GoalPlaceFrom) o).spots);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 31 + spots.hashCode();
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlaceFrom{x=%s,y=%s,z=%s,spots=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z),
                    spots.size()
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                // hb: nothing at the feet (a ladder wants a wall), but it places somewhere: the block
                result.add(((BlockItem) stack.getItem()).getBlock().defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    // hb SLAB_TYPE_KEPT: a slab's type (bottom, top, double) is its shape, not a detail: ignoring "type"
    // (a chest's single, left or right, which sneaking to place it settles), a top slab went where a
    // bottom one goes, a path broke it to walk through, and the builder put it back, round and round
    private static boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            return first.equals(second); // early return if no properties are being ignored
        }
        for (Property<?> prop : first.getProperties()) {
            if (first.getValue(prop) != second.getValue(prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    && !(ignoredProps.contains(prop.getName()) && prop != SlabBlock.TYPE)) { // hb: see SLAB_TYPE_KEPT
                return false;
            }
        }
        return true;
    }

    /**
     * hb: an item whose block is {@code have} can place {@code desired} in some orientation: the same
     * block, the same in every property but those set by the placing (PLACED_PROPS) and the ignored.
     * approxPlaceable tries each item once, at the player's feet with the player's look, so a chest
     * counted only while the player happened to face the right way, and a ladder (no wall there)
     * never; the placing itself checks the orientation (possibleToPlace, placesAsWanted).
     */
    /**
     * hb: WALL_VARIANT. What an item places here: for one that places a block of its own on a wall (a
     * redstone torch, a redstone_wall_torch on a wall) what StandingAndWallBlockItem does, the nearest
     * looking directions in turn, the standing block for the one it hangs from (down) and the wall one
     * for the rest, the first that stays. The builder asked the item's block, the standing torch, and
     * took every wall torch for missing; asked the wall block only, it put a standing torch where it
     * looked down.
     */
    private static BlockState stateFor(BlockItem item, BlockPlaceContext ctx) {
        Block standing = item.getBlock();
        Optional<Block> wall = WALL_BLOCKS.computeIfAbsent(standing, b -> BuiltInRegistries.BLOCK.stream()
                .filter(o -> o != b && o.asItem() == b.asItem() && o.asItem() != Items.AIR).findFirst());
        if (wall.isEmpty()) {
            return standing.getStateForPlacement(ctx);
        }
        Direction hangs = item instanceof HangingSignItem ? Direction.UP : Direction.DOWN;
        BlockState wallState = wall.get().getStateForPlacement(ctx);
        for (Direction d : ctx.getNearestLookingDirections()) {
            if (d == hangs.getOpposite()) {
                continue;
            }
            BlockState state = d == hangs ? standing.getStateForPlacement(ctx) : wallState;
            if (state != null && state.canSurvive(ctx.getLevel(), ctx.getClickedPos())) {
                return state;
            }
        }
        return null;
    }

    private static final Map<Block, Optional<Block>> WALL_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean couldPlace(BlockState have, BlockState desired) {
        if (have.getBlock() != desired.getBlock()) {
            // hb: WALL_VARIANT: the same item places both
            return have.getBlock().asItem() != Items.AIR && have.getBlock().asItem() == desired.getBlock().asItem();
        }
        for (Property<?> prop : desired.getProperties()) {
            if (!PLACED_PROPS.contains(prop.getName()) && !Baritone.settings().buildIgnoreProperties.value.contains(prop.getName())
                    && !have.getValue(prop).equals(desired.getValue(prop))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        // hb: a piston of the build moving (the build's own redstone firing it) is that piston: taken for
        // wrong, it was to break, and the wrong count, changing with it, kept noteIdleSpot from ever
        // seeing the player do nothing
        if (current.is(Blocks.MOVING_PISTON) && desired.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (!props.isEmpty()) { // hb: PROPS
                Prop prop = props.get(BetterBlockPos.longHash(x, y, z));
                if (prop != null) {
                    return prop.clear() ? Blocks.AIR.defaultBlockState() : current.isSolidRender() ? current : prop.block();
                }
            }
            return schematicAt(x, y, z, current);
        }

        // hb: PROPS: the schematic's own, no props
        private BlockState schematicAt(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        // hb: a block of the build around this one, in or to come, hangs on it
        private boolean holdsUp(int x, int y, int z) {
            for (Direction d : Direction.values()) {
                int ax = x + d.getStepX(), ay = y + d.getStepY(), az = z + d.getStepZ();
                BlockState sch = getSchematic(ax, ay, az, bsi.get0(ax, ay, az));
                if (sch != null && hangsFrom(sch) == d.getOpposite()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (!MovementHelper.canWalkOn(bsi, x, y, z, sch) || facesAsPlaced(sch)) {
                    // hb: a movement can't stand on this block or set its facing, so it would place a
                    // throwaway (see placeAt), a wrong block the builder breaks again: a pillar put
                    // cobblestone where a creaking heart goes, a descent broke it, and round again.
                    // Not there, then, whether the block is on the hotbar or not
                    return COST_INF;
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    // hb: plain rock (a full, opaque block, nothing to its placing) is dug through to get at
                    // sealed-in work and put back for next to nothing; the farm's own blocks (a slab, a
                    // hopper, a piston) never: a path went through a hopper to stand where it was, and
                    // another's head through a piston just placed. One penalty for all, at 10 the far
                    // end of a farm sealed in stone went unreached, at 2 paths broke the slab floor
                    double penalty = Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                    if (plain(current) && holdsUp(x, y, z)) {
                        // hb: rock a block of the build hangs on (a button on its side, a repeater on its top):
                        // broken, that block drops; dug through for a way to another button, put back by the
                        // builder, dug through again, the rest of the attempt
                        return COST_INF;
                    }
                    return plain(current) ? Math.min(PLAIN_BREAK_PENALTY, penalty) : COST_INF;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
