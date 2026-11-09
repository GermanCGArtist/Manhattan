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
        if (bad > 0) System.exit(1);
    }
}
