package dev.rayvox.render;

/** Composite render types append texture/state descriptions to their names. */
public final class RenderTypePolicy {
    private RenderTypePolicy(){}
    public static String name(String description){int bracket=description.indexOf('[');return bracket<0?description:description.substring(0,bracket);}
    public static boolean capture(String description) {
        String name=name(description);
        return !name.contains("line")&&!name.contains("crumbling")&&!name.contains("glint")&&!name.contains("shadow")
                &&!name.contains("outline")&&!name.contains("debug")&&!name.startsWith("text")&&!name.contains("weather")&&!name.contains("world_border");
    }
}
