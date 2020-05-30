package minecraftbyexample.mbe06_redstone.input_and_output;

import minecraftbyexample.mbe06_redstone.StartupCommon;
import minecraftbyexample.usefultools.UsefulFunctions;
import net.minecraft.block.Block;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.Iterator;

/**
 * This TileEntity is used for two main purposes:
 *  1) to store the current power level.  This is necessary due to the way that the redstone signals propagate,
 *        e.g. getWeakPower() must retrieve a stored value and not calculate it from neighbours.
 *        see here for more information http://greyminecraftcoder.blogspot.com/2020/05/redstone-1152.html
 *  2) It's alo used to flash the output at a defined rate using block tick scheduling.
 */
public class TileEntityRedstoneMeter extends TileEntity {

  public TileEntityRedstoneMeter() {
    super(StartupCommon.tileEntityDataTypeMBE06);
  }

//  // Retrieve the current power level of the meter - the maximum of the four sides (don't look up or down)
//  // Intended to be called by the renderer, which may be in its own thread.
//  // I'm very wary of using any world methods from render threads, which is why I avoid using this.world.
//	public int calculatePowerLevelClient(World worldIn) {
//
////    int powerLevel = this.worldObj.isBlockIndirectlyGettingPowered(this.pos);  // if input can come from any side, use this line
//
//    int maxPowerFound = 0;
//    final Direction [] HORIZONTAL_DIRECTIONS = new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
//    // can also use Direction.Plane.HORIZONTAL.iterator() if you prefer
//    for (Direction whichFace : HORIZONTAL_DIRECTIONS) {
//      BlockPos neighborPos = pos.offset(whichFace);
//      int powerLevel = worldIn.getRedstonePower(neighborPos, whichFace);
//      maxPowerFound = Math.max(powerLevel, maxPowerFound);
//    }
//    return maxPowerFound;
//  }

  // return the smoothed position of the needle, based on the power level
  public double getSmoothedNeedlePosition() {
    return smoothNeedleMovement.getSmoothedNeedlePosition();
  }

  private void updateNeedleFromPowerLevel() {
    if (storedPowerLevel != lastPowerLevel) {
      lastPowerLevel = storedPowerLevel;
      double targetNeedlePosition = storedPowerLevel / 15.0;
      smoothNeedleMovement.setTargetNeedlePosition(targetNeedlePosition, false);
    }
  }

  private final double NEEDLE_ACCELERATION = 0.4; // acceleration in units per square second
  private final double NEEDLE_MAX_SPEED = 0.4;    // maximum needle movement speed in units per second
  private SmoothNeedleMovement smoothNeedleMovement = new SmoothNeedleMovement(NEEDLE_ACCELERATION, NEEDLE_MAX_SPEED);
  private int lastPowerLevel = -1;

  // -------- server side methods used to keep track of the current power level and alter the output signal state

  public boolean getOutputState()
  {
    return scheduledTogglingOutput.isOn();
  }

  /** whenever a scheduled block update occurs, call this method
   *
   */
  public void onScheduledTick(ServerWorld world, BlockPos pos, Block block)
  {
    scheduledTogglingOutput.onUpdateTick(world, pos, block);
  }

   /**
   *  Change the stored power level (and alters the flashing rate of the power output)
   */
  public void setPowerLevelServer(int newPowerLevel)
  {
    if (newPowerLevel == storedPowerLevel) return;
    storedPowerLevel = newPowerLevel;
    if (newPowerLevel == 0) {   // always off
      scheduledTogglingOutput.setSteadyOutput(false);
    } else if (newPowerLevel == 15) { // always on
      scheduledTogglingOutput.setSteadyOutput(true);
    } else {
          // flashing: slowest = 1 seconds in 4 seconds; fastest = 0.25 seconds in 0.5 seconds.
      final int LOWEST_POWER = 1;
      final int HIGHEST_POWER = 14;
      final int SLOWEST_ON_TIME = 20; // ticks
      final int FASTEST_ON_TIME = 5; // ticks
      final int SLOWEST_PERIOD = 80; // ticks
      final int FASTEST_PERIOD = 10;  // ticks
      int periodTicks = (int)UsefulFunctions.interpolate_with_clipping(newPowerLevel, LOWEST_POWER, HIGHEST_POWER, SLOWEST_PERIOD, FASTEST_PERIOD);
      int onTicks = (int) UsefulFunctions
              .interpolate_with_clipping(newPowerLevel, LOWEST_POWER, HIGHEST_POWER, SLOWEST_ON_TIME, FASTEST_ON_TIME);
      scheduledTogglingOutput.setToggleRate(this.getWorld(), this.getPos(), this.getBlockState().getBlock(), onTicks, periodTicks);
    }
  }

  private ScheduledTogglingOutput scheduledTogglingOutput = new ScheduledTogglingOutput();

  private final int MIN_POWER_LEVEL = 0;
  private final int MAX_POWER_LEVEL = 15;
  private int storedPowerLevel;

  //---------- general TileEntity methods

  // When the world loads from disk, the server needs to send the TileEntity information to the client
  //  it uses getUpdatePacket(), getUpdateTag(), onDataPacket(), and handleUpdateTag() to do this:
  //  getUpdatePacket() and onDataPacket() are used for one-at-a-time TileEntity updates
  //  getUpdateTag() and handleUpdateTag() are used by vanilla to collate together into a single chunk update packet
  @Override
  @Nullable
  public SUpdateTileEntityPacket getUpdatePacket()
  {
    CompoundNBT updateTagDescribingTileEntityState = getUpdateTag();
    int tileEntityType = 6;  // arbitrary number; only used for vanilla TileEntities.  You can use it, or not, as you want.
    return new SUpdateTileEntityPacket(this.pos, tileEntityType, updateTagDescribingTileEntityState);
  }

  @Override
  public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt) {
    read(pkt.getNbtCompound());
    updateNeedleFromPowerLevel();
  }

  /* Creates a tag containing the TileEntity information, used by vanilla to transmit from server to client
     Warning - although our getUpdatePacket() uses this method, vanilla also calls it directly, so don't remove it.
   */
  @Override
  public CompoundNBT getUpdateTag()  {
    CompoundNBT nbtTagCompound = new CompoundNBT();
    write(nbtTagCompound);
    return nbtTagCompound;
  }

  /* Populates this TileEntity with information from the tag, used by vanilla to transmit from server to client
   Warning - although our onDataPacket() uses this method, vanilla also calls it directly, so don't remove it.
 */
  @Override
  public void handleUpdateTag(CompoundNBT tag) {
    this.read(tag);
    updateNeedleFromPowerLevel();
  }

  // This is where you save any data that you don't want to lose when the tile entity unloads
  @Override
  public CompoundNBT write(CompoundNBT parentNBTTagCompound)
  {
    super.write(parentNBTTagCompound); // The super call is required to save the tiles location
    parentNBTTagCompound.putInt("storedPowerLevel", storedPowerLevel);
    return parentNBTTagCompound;
  }

  // This is where you load the data that you saved in writeToNBT
  @Override
  public void read(CompoundNBT parentNBTTagCompound)
  {
    super.read(parentNBTTagCompound); // The super call is required to load the tiles location
    storedPowerLevel = parentNBTTagCompound.getInt("storedPowerLevel");  // defaults to 0 if not found
    if (storedPowerLevel < MIN_POWER_LEVEL ) storedPowerLevel = MIN_POWER_LEVEL;
    if (storedPowerLevel > MAX_POWER_LEVEL ) storedPowerLevel = MAX_POWER_LEVEL;
  }

  /** Return an appropriate bounding box enclosing the TER
	 * This method is used to control whether the TER should be rendered or not, depending on where the player is looking.
	 * The default is the AABB for the parent block, which might be too small if the TER renders outside the borders of the
	 *   parent block.
	 * If you get the boundary too small, the TER may disappear when you aren't looking directly at it.
	 * @return an appropriately size AABB for the TileEntity
	 */
	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		// if your render should always be performed regardless of where the player is looking, use infinite
		AxisAlignedBB infiniteExample = INFINITE_EXTENT_AABB;

		// our needles are all on the block faces so our bounding box is from [x,y,z] to  [x+1, y+1, z+1]
		AxisAlignedBB aabb = new AxisAlignedBB(getPos(), getPos().add(1, 1, 1));
		return aabb;
	}

  /**
   * Don't render the needle if the player is too far away
   * @return the maximum distance squared at which the TER should render
   */
  @Override
  public double getMaxRenderDistanceSquared()
  {
    final int MAXIMUM_DISTANCE_IN_BLOCKS = 32;
    return MAXIMUM_DISTANCE_IN_BLOCKS * MAXIMUM_DISTANCE_IN_BLOCKS;
  }

}
