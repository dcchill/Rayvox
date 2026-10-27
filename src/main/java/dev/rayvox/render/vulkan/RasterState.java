package dev.rayvox.render.vulkan;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.VK13.*;

record RasterState(boolean fill,boolean blend,boolean depth,boolean depthWrite,boolean cull,int cullFace,int front,int depthFunction,
                   int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha,int rgbOp,int alphaOp,int colorMask,
                   boolean offset,float offsetFactor,float offsetUnits) {
    static RasterState capture() {
        var mask=org.lwjgl.BufferUtils.createByteBuffer(4);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
        int bits=0;for(int i=0;i<4;i++)if(mask.get(i)!=0)bits|=1<<i;
        int[] polygon=new int[2];glGetIntegerv(GL_POLYGON_MODE,polygon);
        return new RasterState(polygon[0]==GL_FILL&&polygon[1]==GL_FILL,glIsEnabled(GL_BLEND),glIsEnabled(GL_DEPTH_TEST),glGetBoolean(GL_DEPTH_WRITEMASK),glIsEnabled(GL_CULL_FACE),glGetInteger(GL_CULL_FACE_MODE),glGetInteger(GL_FRONT_FACE),glGetInteger(GL_DEPTH_FUNC),
                glGetInteger(GL_BLEND_SRC_RGB),glGetInteger(GL_BLEND_DST_RGB),glGetInteger(GL_BLEND_SRC_ALPHA),glGetInteger(GL_BLEND_DST_ALPHA),glGetInteger(GL_BLEND_EQUATION_RGB),glGetInteger(GL_BLEND_EQUATION_ALPHA),bits,
                glIsEnabled(GL_POLYGON_OFFSET_FILL),glGetFloat(GL_POLYGON_OFFSET_FACTOR),glGetFloat(GL_POLYGON_OFFSET_UNITS));
    }
    static int factor(int gl) {
        return switch(gl) {
            case GL_ZERO->VK_BLEND_FACTOR_ZERO;case GL_ONE->VK_BLEND_FACTOR_ONE;
            case GL_SRC_COLOR->VK_BLEND_FACTOR_SRC_COLOR;case GL_ONE_MINUS_SRC_COLOR->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GL_DST_COLOR->VK_BLEND_FACTOR_DST_COLOR;case GL_ONE_MINUS_DST_COLOR->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case GL_SRC_ALPHA->VK_BLEND_FACTOR_SRC_ALPHA;case GL_ONE_MINUS_SRC_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GL_DST_ALPHA->VK_BLEND_FACTOR_DST_ALPHA;case GL_ONE_MINUS_DST_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GL_CONSTANT_COLOR->VK_BLEND_FACTOR_CONSTANT_COLOR;case GL_ONE_MINUS_CONSTANT_COLOR->VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case GL_CONSTANT_ALPHA->VK_BLEND_FACTOR_CONSTANT_ALPHA;case GL_ONE_MINUS_CONSTANT_ALPHA->VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case GL_SRC_ALPHA_SATURATE->VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;default->throw new IllegalArgumentException("Unsupported blend factor "+gl);
        };
    }
    static int operation(int gl) {
        return switch(gl) {case GL_FUNC_ADD->VK_BLEND_OP_ADD;case GL_FUNC_SUBTRACT->VK_BLEND_OP_SUBTRACT;case GL_FUNC_REVERSE_SUBTRACT->VK_BLEND_OP_REVERSE_SUBTRACT;case GL_MIN->VK_BLEND_OP_MIN;case GL_MAX->VK_BLEND_OP_MAX;default->throw new IllegalArgumentException("Unsupported blend equation "+gl);};
    }
}
