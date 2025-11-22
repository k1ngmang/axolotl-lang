package org.axl.maven;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the previous run generated, stored in `target/axl/*.state.json`: per .axl file its hash and outputs. */
final class GenerationState {

    static final class Entry {
        String hash;
        List<String> outputs;

        Entry(String hash, List<String> outputs) {
            this.hash = hash;
            this.outputs = outputs;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    int version = 1;
    String environment = "";
    Map<String, Entry> files = new TreeMap<>();

    static GenerationState read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new GenerationState();
        }
        GenerationState state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), GenerationState.class);
        if (state == null || state.files == null) {
            return new GenerationState();
        }
        if (state.environment == null) {
            state.environment = "";
        }
        return state;
    }

    void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
    }
}
