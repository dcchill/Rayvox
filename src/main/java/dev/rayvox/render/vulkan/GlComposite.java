package dev.rayvox.render.vulkan;

import static org.lwjgl.opengl.GL33C.*;

/** Preserves raw GL state because Minecraft's RenderSystem caches these values. */
final class GlComposite implements AutoCloseable {
    private final int program,vao;
    GlComposite() {
        int vertex=shader(GL_VERTEX_SHADER,"#version 330 core\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}");
        int fragment=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec2 uv;uniform sampler2D colorImage;uniform sampler2D depthImage;out vec4 color;void main(){color=texture(colorImage,uv);if(color.a<.5)discard;gl_FragDepth=texture(depthImage,uv).r;}");
        program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);glDeleteShader(vertex);glDeleteShader(fragment);
        if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
        vao=glGenVertexArrays();
    }
    private static int shader(int type,String source){int id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);if(glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE){String error=glGetShaderInfoLog(id);glDeleteShader(id);throw new IllegalStateException(error);}return id;}
    void draw(SharedSurface surface,int width,int height) {
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),func=glGetInteger(GL_DEPTH_FUNC);
        boolean blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        glActiveTexture(GL_TEXTURE0);int texture0=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE1);int texture1=glGetInteger(GL_TEXTURE_BINDING_2D);
        surface.waitForVulkan();
        try {
            glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);glDepthMask(true);
            glUseProgram(program);glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,surface.colorTexture);glUniform1i(glGetUniformLocation(program,"colorImage"),0);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,surface.depthTexture);glUniform1i(glGetUniformLocation(program,"depthImage"),1);glDrawArrays(GL_TRIANGLES,0,3);
        } finally {
            glBindTexture(GL_TEXTURE_2D,texture1);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture0);glActiveTexture(active);
            glUseProgram(oldProgram);glBindVertexArray(oldVao);glDepthFunc(func);glDepthMask(mask);
            enable(GL_BLEND,blend);enable(GL_DEPTH_TEST,depth);enable(GL_CULL_FACE,cull);enable(GL_SCISSOR_TEST,scissor);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }
    private static void enable(int cap,boolean enabled){if(enabled)glEnable(cap);else glDisable(cap);}
    @Override public void close(){glDeleteVertexArrays(vao);glDeleteProgram(program);}
}
