package dev.rayvox.render.vulkan;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderInstance;

/** Ports declarations only; Minecraft's loaded shader bodies implement vanilla lighting. */
final class RasterShader {
    private static final Pattern UNIFORM=Pattern.compile("(?m)^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern IO=Pattern.compile("(?m)^(\\s*)(?:(flat|smooth|noperspective)\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    record Field(String type,String name,int offset,int size){}
    final List<Field> fields=new ArrayList<>();
    final String vertex,fragment;
    final int bytes;

    RasterShader(String vertex,String fragment,List<String> attributes) {
        var definitions=new LinkedHashMap<String,String>();
        for(var source:List.of(vertex,fragment)) {
            var matcher=UNIFORM.matcher(source);
            while(matcher.find()) {
                String type=matcher.group(1),name=matcher.group(2);
                if(type.startsWith("sampler")) {
                    if(!type.equals("sampler2D")||!name.matches("Sampler[012]"))throw new IllegalArgumentException("Unsupported sampler "+name);
                } else {
                    String old=definitions.putIfAbsent(name,type);
                    if(old!=null&&!old.equals(type))throw new IllegalArgumentException("Inconsistent uniform "+name);
                }
            }
        }
        int offset=0;
        var block=new StringBuilder("layout(std140,set=0,binding=0) uniform VanillaUniforms {\n");
        for(var entry:definitions.entrySet()) {
            String type=entry.getValue();int size=size(type),alignment=type.equals("float")||type.equals("int")?4:type.endsWith("2")?8:16;
            offset=(offset+alignment-1)&-alignment;
            fields.add(new Field(type,entry.getKey(),offset,size));
            block.append("layout(offset=").append(offset).append(") ").append(type).append(' ').append(entry.getKey()).append(";\n");offset+=size;
        }
        if(fields.isEmpty())block.append("vec4 unused;\n");
        block.append("};\n");bytes=Math.max(16,(offset+15)&~15);
        var varyings=new LinkedHashMap<String,Integer>();
        var matcher=IO.matcher(vertex);
        while(matcher.find())if(matcher.group(3).equals("out"))varyings.put(matcher.group(5),varyings.size());
        this.vertex=port(vertex,true,attributes,varyings,block.toString());
        this.fragment=port(fragment,false,attributes,varyings,block.toString());
    }
    private static int size(String type) {
        return switch(type) {
            case "float","int"->4;case "vec2","ivec2"->8;case "vec3","ivec3","vec4","ivec4"->16;
            case "mat3"->48;case "mat4"->64;default->throw new IllegalArgumentException("Unsupported uniform type "+type);
        };
    }
    private static String port(String source,boolean vertex,List<String> attributes,Map<String,Integer> varyings,String block) {
        source=source.replaceAll("(?m)^\\s*#version[^\\r\\n]*","");
        var uniforms=UNIFORM.matcher(source);var out=new StringBuilder();
        while(uniforms.find()) {
            String replacement="";
            if(uniforms.group(1).equals("sampler2D"))replacement="layout(set=0,binding="+(Integer.parseInt(uniforms.group(2).substring(7))+1)+") uniform sampler2D "+uniforms.group(2)+";";
            uniforms.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        uniforms.appendTail(out);source=out.toString();
        var io=IO.matcher(source);out=new StringBuilder();
        while(io.find()) {
            String name=io.group(5);int location;
            if(vertex&&io.group(3).equals("in"))location=attributes.indexOf(name);
            else if(!vertex&&io.group(3).equals("out"))location=0;
            else location=varyings.getOrDefault(name,-1);
            if(location<0)throw new IllegalArgumentException("Unmatched shader input "+name);
            io.appendReplacement(out,"layout(location="+location+") "+(io.group(2)==null?"":io.group(2)+" ")+io.group(3)+" "+io.group(4)+" "+name+";");
        }
        io.appendTail(out);source=out.toString().replace("gl_VertexID","gl_VertexIndex");
        if(vertex) {
            var main=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{").matcher(source);
            if(!main.find())throw new IllegalArgumentException("Missing vertex main");
            int end=main.end(),depth=1;
            for(;end<source.length()&&depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
            if(depth!=0)throw new IllegalArgumentException("Unclosed vertex main");
            end--;
            source=source.substring(0,end)+"\ngl_Position.z=(gl_Position.z+gl_Position.w)*0.5;\n"+source.substring(end);
        }
        return "#version 450\n"+block+source;
    }
    void write(ShaderInstance shader,ByteBuffer destination,int base) {
        for(var field:fields) {
            var uniform=shader.getUniform(field.name());
            if(uniform==null)throw new IllegalArgumentException("Missing uniform "+field.name());
            int offset=base+field.offset();
            if(field.type().equals("int")||field.type().startsWith("ivec")) {
                var values=uniform.getIntBuffer();for(int i=0;i<values.capacity();i++)destination.putInt(offset+i*4,values.get(i));
            } else {
                var values=uniform.getFloatBuffer();
                for(int i=0;i<values.capacity();i++)destination.putFloat(offset+(field.type().equals("mat3")?(i/3*4+i%3):i)*4,values.get(i));
            }
        }
    }
}
