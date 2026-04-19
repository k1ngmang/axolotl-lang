package org.axl.javac;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hints for unknown names: the Axolotl or Java name for a habit from Kotlin, an import, or a close spelling. */
final class Names {

    private static final Set<String> ARRAY_TYPES = Set.of("Array", "IntArray", "LongArray", "DoubleArray",
            "BooleanArray", "CharArray");

    private static final Map<String, String> JDK_TYPES = Map.ofEntries(
            Map.entry("ArrayList", "java.util"), Map.entry("LinkedList", "java.util"), Map.entry("HashMap", "java.util"),
            Map.entry("LinkedHashMap", "java.util"), Map.entry("TreeMap", "java.util"), Map.entry("HashSet", "java.util"),
            Map.entry("LinkedHashSet", "java.util"), Map.entry("TreeSet", "java.util"), Map.entry("ArrayDeque", "java.util"),
            Map.entry("List", "java.util"), Map.entry("Map", "java.util"), Map.entry("Set", "java.util"),
            Map.entry("Optional", "java.util"), Map.entry("Objects", "java.util"), Map.entry("Arrays", "java.util"),
            Map.entry("Collections", "java.util"), Map.entry("Scanner", "java.util"), Map.entry("Random", "java.util"),
            Map.entry("Function", "java.util.function"), Map.entry("Supplier", "java.util.function"),
            Map.entry("Consumer", "java.util.function"), Map.entry("Predicate", "java.util.function"),
            Map.entry("Stream", "java.util.stream"), Map.entry("Collectors", "java.util.stream"),
            Map.entry("IntStream", "java.util.stream"), Map.entry("Path", "java.nio.file"),
            Map.entry("Files", "java.nio.file"), Map.entry("LocalDate", "java.time"), Map.entry("Duration", "java.time"));

    private static final Map<String, String> FUNCTIONS = Map.of("print", "did you mean `println`?",
            "listOf", "write `List.of(...)`", "mutableListOf", "write `ArrayList<T>()`", "arrayOf", "write `List.of(...)`",
            "mapOf", "write `Map.of(...)`", "setOf", "write `Set.of(...)`", "require", "throw an `IllegalArgumentException`",
            "check", "throw an `IllegalStateException`");

    private static final Set<String> JAVA_WORDS = Set.of("final", "var", "return", "public", "private", "static",
            "class", "new", "this", "null", "true", "false", "void", "else", "case", "default", "switch", "record",
            "interface", "enum", "import", "package", "while", "for", "if");

    static final String NO_TYPE_PARAMETERS = "Axolotl functions and classes have no type parameters, so write the type itself";

    private static final Pattern WORD = Pattern.compile("(?<![\\w$.])[A-Za-z_]\\w*");

    private Names() {}

    static String unknownType(String symbol) {
        String hint = switch (symbol) {
            case "Any" -> "write `Object`";
            case "Bool" -> "write `Boolean`";
            case "Char" -> "a character is a `Character`";
            case "Unit" -> "leave out the result type of a function that returns nothing";
            default -> symbol.matches("[A-Z]") ? NO_TYPE_PARAMETERS
                    : ARRAY_TYPES.contains(symbol) ? "Axolotl uses lists, such as `List<Int>`"
                    : JDK_TYPES.containsKey(symbol) ? "add `import " + JDK_TYPES.get(symbol) + "." + symbol + "`"
                    : "check the spelling or add an import";
        };
        return "unknown type `" + symbol + "`; " + hint;
    }

    /** `Int.MAX_VALUE`: Axolotl's type names are not classes. */
    static String typeHint(String symbol) {
        return switch (symbol) {
            case "Int" -> "the constants of `Int` are in `Integer`, such as `Integer.MAX_VALUE`";
            case "Long", "Double" -> "the constants of `" + symbol + "` are in `java.lang." + symbol
                    + "`, such as `java.lang." + symbol + ".MAX_VALUE`";
            default -> JDK_TYPES.containsKey(symbol) ? "add `import " + JDK_TYPES.get(symbol) + "." + symbol + "`" : null;
        };
    }

    static String functionHint(String name) {
        return FUNCTIONS.get(name);
    }

    /** A name of the generated file one or two letters away from the unknown one, or null. */
    static String similar(String name, String[] javaLines) {
        if (name.length() < 3 || Character.isUpperCase(name.charAt(0))) {
            return null;
        }
        for (String line : javaLines) {
            Matcher word = WORD.matcher(line.replaceAll("\"[^\"\\\\]*+(\\\\.[^\"\\\\]*+)*+\"", "\"\""));
            while (word.find()) {
                String candidate = word.group();
                if (!candidate.equals(name) && !JAVA_WORDS.contains(candidate)
                        && distance(name, candidate) <= (name.length() > 4 ? 2 : 1)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Edits that turn one word into the other, a swap of neighbours counting as one. */
    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0 || j == 0) {
                    d[i][j] = i + j;
                    continue;
                }
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
