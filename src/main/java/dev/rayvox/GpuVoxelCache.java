package dev.rayvox;

import java.nio.ByteBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** Bounded material/albedo, subcell shape, and section validity textures. */
public final class GpuVoxelCache implements AutoCloseable {
    public final int width;
    public final int voxels;
    public final int states;
    public final int shapes;
    private final ByteBuffer section = BufferUtils.createByteBuffer(VoxelGrid.SECTION_BYTES * 4);
    private final java.nio.IntBuffer shapeData = BufferUtils.createIntBuffer(VoxelGrid.SECTION_BYTES * 2);
    private final ByteBuffer metadata;
    private final boolean[] airSnapshots;
    private static final byte[] AIR = new byte[VoxelGrid.SECTION_BYTES];
    private int next;
    public int uploadedLastFrame;
    public int uploadedBytesLastFrame;

    public GpuVoxelCache(int width) {
        RenderSystem.assertOnRenderThread();
        if (width < 3 || width > 9 || width % 2 == 0) throw new IllegalArgumentException("Invalid section width");
        this.width = width;
        metadata = BufferUtils.createByteBuffer(width * width * width);
        airSnapshots = new boolean[width * width * width];
        var saved = new UploadState();
        int data = 0, status = 0, shape = 0;
        try {
            data = allocate(width * 16, GL_RGBA8UI, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE);
            shape = allocate(width * 16, GL_RG32UI, GL_RG_INTEGER, GL_UNSIGNED_INT);
            status = allocate(width, GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE);
            glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, width, width, width, GL_RED_INTEGER, GL_UNSIGNED_BYTE, metadata);
        } catch (RuntimeException failure) {
            glDeleteTextures(data);
            glDeleteTextures(status);
            glDeleteTextures(shape);
            throw failure;
        } finally { saved.restore(); }
        voxels = data;
        states = status;
        shapes = shape;
    }

    private static int allocate(int side, int internal, int format, int type) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, texture);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexImage3D(GL_TEXTURE_3D, 0, internal, side, side, side, 0, format, type, (ByteBuffer)null);
        if (glGetTexLevelParameteri(GL_TEXTURE_3D, 0, GL_TEXTURE_WIDTH) != side) {
            glDeleteTextures(texture);
            throw new IllegalStateException("Unable to allocate voxel texture");
        }
        return texture;
    }

    public void upload(VoxelGrid grid, int budget) {
        RenderSystem.assertOnRenderThread();
        if (grid.width != width) throw new IllegalArgumentException("CPU/GPU cache dimensions differ");
        uploadedLastFrame = 0;
        uploadedBytesLastFrame = 0;
        boolean changed = false;
        for (int i = 0; i < grid.slots.length; i++) {
            if (grid.slots[i].ready || metadata.get(i) != (byte)grid.slots[i].state()) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        var saved = new UploadState();
        try {
            glBindTexture(GL_TEXTURE_3D, voxels);
            for (int scanned = 0; scanned < grid.slots.length && uploadedLastFrame < budget; scanned++) {
                int index = next;
                var slot = grid.slots[index];
                next = (next + 1) % grid.slots.length;
                if (!slot.ready || slot.dirty) continue;
                section.clear(); shapeData.clear();
                boolean needsShapes = false;
                boolean rayEmpty = true;
                // Physical ring slots retain their texels across recentering. Air has no observable albedo/shape.
                boolean retainedAir = airSnapshots[index] && java.util.Arrays.equals(slot.data, AIR);
                if (!retainedAir) {
                    boolean allAir = true;
                    for (int i = 0; i < VoxelGrid.SECTION_BYTES; i++) {
                        section.put(slot.data[i]).put(slot.albedo[i * 3]).put(slot.albedo[i * 3 + 1]).put(slot.albedo[i * 3 + 2]);
                        int material = Byte.toUnsignedInt(slot.data[i]) & BoxShape.MATERIAL_MASK;
                        allAir &= slot.data[i] == 0;
                        needsShapes |= material == 2 || material == 3 || material >= 16;
                        rayEmpty &= material == 0 || material >= 4 && material < 16;
                    }
                    section.flip();
                    glBindTexture(GL_TEXTURE_3D, voxels);
                    glTexSubImage3D(GL_TEXTURE_3D, 0, Math.floorMod(slot.x, width) * 16,
                            Math.floorMod(slot.y, width) * 16, Math.floorMod(slot.z, width) * 16,
                            16, 16, 16, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, section);
                    uploadedBytesLastFrame += VoxelGrid.SECTION_BYTES * 4;
                    airSnapshots[index] = allAir;
                }
                // A material snapshot without partial geometry or filters never samples the shape texture.
                if (needsShapes) {
                    for (long mask : slot.shapes) shapeData.put((int)mask).put((int)(mask >>> 32));
                    shapeData.flip();
                    glBindTexture(GL_TEXTURE_3D, shapes);
                    glTexSubImage3D(GL_TEXTURE_3D, 0, Math.floorMod(slot.x, width) * 16,
                            Math.floorMod(slot.y, width) * 16, Math.floorMod(slot.z, width) * 16,
                            16, 16, 16, GL_RG_INTEGER, GL_UNSIGNED_INT, shapeData);
                    uploadedBytesLastFrame += VoxelGrid.SECTION_BYTES * 8;
                }
                slot.ready = false;
                slot.uploaded = true;
                slot.uploadedRayEmpty = rayEmpty;
                slot.uploadedLights = slot.lights;
                slot.uploadedLightOverflow = slot.lightOverflow;
                uploadedLastFrame++;
                grid.revision++;
                grid.snapshotRevision++;
            }
            boolean stateChanged = false;
            for (int i = 0; i < grid.slots.length; i++) {
                byte state = (byte)grid.slots[i].state();
                if (metadata.get(i) != state) {
                    if (((metadata.get(i) ^ state) & 1) != 0) grid.snapshotRevision++;
                    metadata.put(i, state);
                    stateChanged = true;
                }
            }
            if (stateChanged) {
                glBindTexture(GL_TEXTURE_3D, states);
                glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, width, width, width, GL_RED_INTEGER, GL_UNSIGNED_BYTE, metadata);
                uploadedBytesLastFrame += metadata.capacity();
            }
        } finally { saved.restore(); }
    }

    public long bytes() { return (long)width * width * width * (VoxelGrid.SECTION_BYTES * 12 + 1); }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        glDeleteTextures(voxels);
        glDeleteTextures(states);
        glDeleteTextures(shapes);
    }

    static final class UploadState {
        final int binding = glGetInteger(GL_TEXTURE_BINDING_3D);
        final int binding2D = glGetInteger(GL_TEXTURE_BINDING_2D);
        final int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        final int buffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        final int imageHeight = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
        final int skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        final int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        final int skipImages = glGetInteger(GL_UNPACK_SKIP_IMAGES);

        UploadState() {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        void restore() {
            glBindTexture(GL_TEXTURE_3D, binding);
            glBindTexture(GL_TEXTURE_2D, binding2D);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer);
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, imageHeight);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, skipImages);
        }
    }
}
