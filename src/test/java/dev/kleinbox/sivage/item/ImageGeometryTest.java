package dev.kleinbox.sivage.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageGeometryTest {
    @Test
    void everySupportedFaceProducesAUniqueRectangleInItsPlacementPlane() {
        BlockPos origin = new BlockPos(10, 20, 30);

        for (Direction direction : Direction.values()) {
            Set<BlockPos> positions = new HashSet<>();
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    BlockPos position = ImageGeometry.offset(origin, direction, -x, y);
                    positions.add(position);
                    switch (direction.getAxis()) {
                        case X -> assertEquals(origin.getX(), position.getX());
                        case Y -> assertEquals(origin.getY(), position.getY());
                        case Z -> assertEquals(origin.getZ(), position.getZ());
                    }
                }
            }
            assertEquals(16, positions.size(), direction.toString());
        }
    }
}
