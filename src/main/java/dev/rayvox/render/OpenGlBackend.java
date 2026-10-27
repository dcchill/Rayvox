package dev.rayvox.render;

/** Existing Rayvox rendering remains driven by its Minecraft hooks. */
public final class OpenGlBackend implements RenderBackend {
    private final String reason;
    private final boolean vanilla;
    public OpenGlBackend(String reason){this(reason,false);}
    public OpenGlBackend(String reason,boolean vanilla){this.reason=reason;this.vanilla=vanilla;}
    public void initialize(){}
    public void update(Scene.Update update){}
    public void resize(int width,int height){}
    public void render(Scene.Camera camera){}
    public Diagnostics diagnostics(){return new Diagnostics(vanilla?"OpenGL vanilla":"OpenGL Rayvox",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),reason,0,0,Double.NaN,0,0,0);}
    public void close(){}
}
