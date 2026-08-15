package dev.kleinbox.sivage.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure geometry shared by placement validation and commit. */
public final class ImageGeometry {
    private ImageGeometry() {
    }

    public static BlockPos offset(BlockPos origin, Direction direction, int offX, int offY) {
        Direction rightDirection;
        Direction upDirection;

        boolean onWall = direction.getAxis().isHorizontal();
        int inverter = onWall ? 1 : -1;

        if (onWall) {
            rightDirection = direction.getClockWise();
            upDirection = Direction.UP;
        } else {
            rightDirection = Direction.EAST;
            upDirection = direction == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        }

        return origin.offset(
                inverter * rightDirection.getStepX() * offX + inverter * upDirection.getStepX() * offY,
                inverter * rightDirection.getStepY() * offX + inverter * upDirection.getStepY() * offY,
                inverter * rightDirection.getStepZ() * offX + inverter * upDirection.getStepZ() * offY
        );
    }
}
