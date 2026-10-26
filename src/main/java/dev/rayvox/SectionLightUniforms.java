package dev.rayvox;

import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;

/** These raw uniforms belong to one linked program and survive its bind/unbind cycles. */
final class SectionLightUniforms {
    private final int countLocation, readyLocation, recordsLocation;
    private final float[] published = new float[LightSourceManager.PER_SECTION * 8];
    private int count = -1, ready = -1;
    private boolean recordsInitialized;

    SectionLightUniforms(int program) {
        countLocation = glGetUniformLocation(program, "FarLightCount");
        readyLocation = glGetUniformLocation(program, "FarReady");
        recordsLocation = glGetUniformLocation(program, "FarLightRecords[0]");
    }

    void publish(int count, boolean ready, float[] records) {
        if (this.count != count) {
            glUniform1i(countLocation, count);
            this.count = count;
        }
        int flag = ready ? 1 : 0;
        if (this.ready != flag) {
            glUniform1i(readyLocation, flag);
            this.ready = flag;
        }
        if (count > 0 && (!recordsInitialized || !Arrays.equals(records, 0, count * 8, published, 0, count * 8))) {
            glUniform4fv(recordsLocation, records);
            System.arraycopy(records, 0, published, 0, published.length);
            recordsInitialized = true;
        }
    }
}
