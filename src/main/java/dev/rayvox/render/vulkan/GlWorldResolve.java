package dev.rayvox.render.vulkan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** World-only spatial resolve on the GL side of the existing Vulkan/GL sharing boundary. */
public final class GlWorldResolve implements AutoCloseable {
    private int program,vao,sampler,scratch,scratchFramebuffer,scratchWidth,scratchHeight;

    public GlWorldResolve() {
        int vertex=0,fragment=0;
        try {
            vertex=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}");
            try(var stream=GlWorldResolve.class.getResourceAsStream("/assets/rayvox/shaders/world_resolve.frag")) {
                if(stream==null)throw new IllegalStateException("Missing world resolve shader");
                fragment=shader(GL_FRAGMENT_SHADER,new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            }
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
            vao=glGenVertexArrays();sampler=glGenSamplers();
            glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glSamplerParameteri(sampler,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glSamplerParameteri(sampler,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        } catch(IOException failure){close();throw new IllegalStateException("Read world resolve shader",failure);}
        catch(RuntimeException|Error failure){close();throw failure;}
        finally{if(vertex!=0)glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);}
    }

    private static int shader(int type,String source) {
        int id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);
        if(glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE){String message=glGetShaderInfoLog(id);glDeleteShader(id);throw new IllegalStateException(message);}return id;
    }

    /** Copies a full-resolution source before filtering, avoiding framebuffer feedback. */
    public void antiAlias(int framebuffer,int texture,int width,int height) {
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int binding=glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean scissor=glIsEnabled(GL_SCISSOR_TEST);
        try {
            scratch(width,height);glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,framebuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,scratchFramebuffer);
            glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_NEAREST);
            draw(scratch,framebuffer,width,height,false,true,0);
        } finally {
            glBindTexture(GL_TEXTURE_2D,binding);glActiveTexture(active);enabled(GL_SCISSOR_TEST,scissor);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        }
    }

    /** Native-sized AA is one pass; upscaling plus AA uses a distinct full-sized intermediate. */
    public void resolve(int texture,int framebuffer,int width,int height,boolean upscale,boolean fxaa,float sharpness) {
        resolve(texture,framebuffer,width,height,upscale,fxaa,sharpness,0,0);
    }
    public void resolve(int texture,int framebuffer,int width,int height,boolean upscale,boolean fxaa,float sharpness,int sourceWidth,int sourceHeight) {
        if(!upscale||!fxaa){draw(texture,framebuffer,width,height,upscale,fxaa,upscale?sharpness:0,sourceWidth,sourceHeight);return;}
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int active=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int binding=glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            scratch(width,height);draw(texture,scratchFramebuffer,width,height,true,false,sharpness,sourceWidth,sourceHeight);
            draw(scratch,framebuffer,width,height,false,true,0);
        } finally {
            glBindTexture(GL_TEXTURE_2D,binding);glActiveTexture(active);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        }
    }

    private void scratch(int width,int height) {
        if(scratch!=0&&scratchWidth==width&&scratchHeight==height)return;
        if(scratch!=0)glDeleteTextures(scratch);if(scratchFramebuffer!=0)glDeleteFramebuffers(scratchFramebuffer);
        scratch=glGenTextures();glBindTexture(GL_TEXTURE_2D,scratch);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,0L);
        scratchFramebuffer=glGenFramebuffers();glBindFramebuffer(GL_DRAW_FRAMEBUFFER,scratchFramebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,scratch,0);
        if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("World resolve framebuffer incomplete");
        scratchWidth=width;scratchHeight=height;
    }

    private void draw(int texture,int framebuffer,int width,int height,boolean upscale,boolean fxaa,float sharpness) {
        draw(texture,framebuffer,width,height,upscale,fxaa,sharpness,0,0);
    }
    private void draw(int texture,int framebuffer,int width,int height,boolean upscale,boolean fxaa,float sharpness,int sourceWidth,int sourceHeight) {
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        var mask=BufferUtils.createByteBuffer(4);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
        boolean blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D),oldSampler=glGetInteger(GL_SAMPLER_BINDING);
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);glViewport(0,0,width,height);glColorMask(true,true,true,true);
            glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_FRAMEBUFFER_SRGB);
            glUseProgram(program);glBindVertexArray(vao);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(0,sampler);
            glUniform1i(glGetUniformLocation(program,"sourceImage"),0);glUniform1i(glGetUniformLocation(program,"upscale"),upscale?1:0);
            glUniform2f(glGetUniformLocation(program,"sourceSize"),sourceWidth,sourceHeight);
            glUniform1i(glGetUniformLocation(program,"fxaa"),fxaa?1:0);glUniform1f(glGetUniformLocation(program,"sharpness"),Math.clamp(sharpness,0,1));
            glDrawArrays(GL_TRIANGLES,0,3);
        } finally {
            glBindSampler(0,oldSampler);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(active);glUseProgram(oldProgram);glBindVertexArray(oldVao);
            glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);
            enabled(GL_BLEND,blend);enabled(GL_DEPTH_TEST,depth);enabled(GL_CULL_FACE,cull);enabled(GL_SCISSOR_TEST,scissor);enabled(GL_STENCIL_TEST,stencil);enabled(GL_FRAMEBUFFER_SRGB,srgb);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);
        }
    }
    private static void enabled(int cap,boolean value){if(value)glEnable(cap);else glDisable(cap);}
    public void close(){if(scratch!=0)glDeleteTextures(scratch);if(scratchFramebuffer!=0)glDeleteFramebuffers(scratchFramebuffer);if(sampler!=0)glDeleteSamplers(sampler);if(vao!=0)glDeleteVertexArrays(vao);if(program!=0)glDeleteProgram(program);scratch=scratchFramebuffer=sampler=vao=program=0;}
}