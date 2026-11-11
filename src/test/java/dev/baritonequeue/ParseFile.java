package dev.baritonequeue;

import java.nio.file.Files;
import java.nio.file.Path;

/** Parse check for script files (gradlew parseScripts): prints each file's result, fails if one does not parse. */
public class ParseFile {
    public static void main(String[] files) throws Exception {
        int bad = 0;
        for (String file : files) {
            String name = Path.of(file).getFileName().toString();
            try {
                System.out.println(name + ": parsed, " + Parser.parse(Files.readString(Path.of(file))).size() + " top-level statements");
            } catch (Parser.ParseError e) {
                System.out.println(name + ": line " + e.line + ": " + e.getMessage());
                bad++;
            }
        }
        // the presets that come with the mod: every one must parse
        for (var e : Presets.defaults().entrySet()) {
            try {
                Parser.parse(String.join("\n", e.getValue()));
            } catch (Parser.ParseError err) {
                System.out.println("preset '" + e.getKey() + "': line " + err.line + ": " + err.getMessage());
                bad++;
            }
        }
        System.out.println(Presets.defaults().size() + " presets parsed" + (bad > 0 ? " (with errors above)" : ""));
        // the upgrade: an untouched old default is replaced, an edited one is kept beside the new one, own ones stay
        var user = new java.util.LinkedHashMap<String, java.util.List<String>>(Presets.v6());
        user.put("Clear trees", java.util.List.of("// my own version"));
        user.put("My own preset", java.util.List.of("print(1)"));
        var up = Presets.upgrade(user);
        boolean ok = up.get("Mine the entire area").equals(Presets.defaults().get("Mine the entire area"))
                && !up.containsKey("Mine the entire area (layers)") && !up.containsKey("Remove water (layers)")
                && up.get("Clear trees (yours)") != null && up.get("Clear trees").equals(Presets.defaults().get("Clear trees"))
                && up.get("My own preset") != null;
        System.out.println("preset upgrade: " + (ok ? "ok" : "WRONG: " + up.keySet()));
        if (!ok) bad++;
        if (bad > 0) System.exit(1);
    }
}
