package dev.rayvox.render.vulkan;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.vulkan.VK13.*;

record RasterSampler(int min,int mag,int s,int t,int maxLevel,float minLod,float maxLod,float bias) {
    static RasterSampler capture() {
        if(glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL)!=0)throw new IllegalArgumentException("Nonzero texture base level");
        return new RasterSampler(glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER),glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER),
                glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S),glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T),glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL),
                glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MIN_LOD),glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MAX_LOD),glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_LOD_BIAS));
    }
    boolean mipmapped(){return min!=GL_NEAREST&&min!=GL_LINEAR;}
    int minFilter(){return min==GL_LINEAR||min==GL_LINEAR_MIPMAP_NEAREST||min==GL_LINEAR_MIPMAP_LINEAR?VK_FILTER_LINEAR:VK_FILTER_NEAREST;}
    int mipMode(){return min==GL_LINEAR_MIPMAP_LINEAR||min==GL_NEAREST_MIPMAP_LINEAR?VK_SAMPLER_MIPMAP_MODE_LINEAR:VK_SAMPLER_MIPMAP_MODE_NEAREST;}
    static int wrap(int gl){return switch(gl){case GL_REPEAT->VK_SAMPLER_ADDRESS_MODE_REPEAT;case GL_MIRRORED_REPEAT->VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;case GL_CLAMP_TO_EDGE->VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;default->throw new IllegalArgumentException("Unsupported texture wrap "+gl);};}
}
