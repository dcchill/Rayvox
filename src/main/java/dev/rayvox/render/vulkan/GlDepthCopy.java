package dev.rayvox.render.vulkan;

import static org.lwjgl.opengl.GL33C.*;

/** Depth blits require identical formats; vanilla's depth texture may be D24 while sharing uses D32F. */
final class GlDepthCopy implements AutoCloseable {
    private int program,vao;
    GlDepthCopy() {
        int vertex=shader(GL_VERTEX_SHADER,"#version 150\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}");
        int fragment=0;
        try {
            fragment=shader(GL_FRAGMENT_SHADER,"#version 150\nin vec2 uv;uniform sampler2D sourceDepth;void main(){gl_FragDepth=texture(sourceDepth,uv).r;}");
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
            vao=glGenVertexArrays();
        }catch(RuntimeException|Error failure){close();throw failure;}
        finally{glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);}
    }
    private static int shader(int type,String source){int id=glCreateShader(type);glShaderSource(id,source);glCompileShader(id);if(glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE){String error=glGetShaderInfoLog(id);glDeleteShader(id);throw new IllegalStateException(error);}return id;}
    void draw(int texture,int width,int height) {
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE),func=glGetInteger(GL_DEPTH_FUNC);
        boolean blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);var colorMask=org.lwjgl.BufferUtils.createByteBuffer(4);glGetBooleanv(GL_COLOR_WRITEMASK,colorMask);
        glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);glDepthMask(true);glColorMask(false,false,false,false);
            glUseProgram(program);glBindVertexArray(vao);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(glGetUniformLocation(program,"sourceDepth"),0);glDrawArrays(GL_TRIANGLES,0,3);
        }finally {
            glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(active);glUseProgram(oldProgram);glBindVertexArray(oldVao);glDepthFunc(func);glDepthMask(mask);
            glColorMask(colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);
            enabled(GL_BLEND,blend);enabled(GL_DEPTH_TEST,depth);enabled(GL_CULL_FACE,cull);enabled(GL_SCISSOR_TEST,scissor);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }
    private static void enabled(int flag,boolean value){if(value)glEnable(flag);else glDisable(flag);}
    public void close(){if(program!=0){glDeleteProgram(program);program=0;}if(vao!=0){glDeleteVertexArrays(vao);vao=0;}}
}
