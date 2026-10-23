package dev.rayvox;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonParser;

/** Resource-pack block IDs mapped to at most 48 unique RGB transmission filters. Values are linear. */
final class MaterialTable {
    final Map<String, Integer> blocks;
    final float[] transmission = new float[64 * 3];

    private MaterialTable(Map<String, Integer> blocks) {
        this.blocks = Map.copyOf(blocks);
        transmission[9] = 0.72f; transmission[10] = 0.86f; transmission[11] = 0.94f;
    }

    static MaterialTable read(Reader reader) {
        var json = JsonParser.parseReader(reader).getAsJsonObject();
        if (json.size() > 4096) throw new IllegalArgumentException("Too many material block IDs");
        var ids = new HashMap<String, Integer>();
        var colors = new java.util.ArrayList<java.util.List<Float>>();
        for (var entry : json.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid material block ID");
            var channels = entry.getValue().getAsJsonObject().getAsJsonArray("transmission");
            if (channels == null || channels.size() != 3) throw new IllegalArgumentException("Transmission needs three channels");
            var rgb = new java.util.ArrayList<Float>(3);
            for (var channel : channels) {
                float value = channel.getAsFloat();
                if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Invalid material transmission");
                rgb.add(value);
            }
            int index = colors.indexOf(rgb);
            if (index < 0) {
                if (colors.size() == 48) throw new IllegalArgumentException("At most 48 unique transmission filters");
                index = colors.size(); colors.add(rgb);
            }
            ids.put(entry.getKey(), 16 + index);
        }
        var table = new MaterialTable(ids);
        for (int i = 0; i < colors.size(); i++) for (int c = 0; c < 3; c++)
            table.transmission[(16 + i) * 3 + c] = colors.get(i).get(c);
        return table;
    }
}
