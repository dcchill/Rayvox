package dev.rayvox;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuVoxelCacheTest {
    @Test void occupancyMarksRayInteractingMaterialsPerFourVoxelRegion() {
        byte[] data = new byte[VoxelGrid.SECTION_BYTES];
        data[VoxelGrid.voxelIndex(0, 0, 0)] = 1;
        data[VoxelGrid.voxelIndex(4, 4, 4)] = 3;
        data[VoxelGrid.voxelIndex(8, 8, 8)] = 7;
        ByteBuffer regions = ByteBuffer.allocate(64);

        assertTrue(GpuVoxelCache.packOccupancy(data, regions));
        assertEquals(1, regions.get(0));
        assertEquals(1, regions.get(21));
        assertEquals(0, regions.get(42));

        java.util.Arrays.fill(data, (byte)0);
        assertFalse(GpuVoxelCache.packOccupancy(data, regions));
        for (int i = 0; i < regions.capacity(); i++) assertEquals(0, regions.get(i));
    }
}
