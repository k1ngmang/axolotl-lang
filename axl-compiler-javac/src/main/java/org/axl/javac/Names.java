package org.axl.javac;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private static final Map<String, String> FUNCTIONS = Map.ofEntries(Map.entry("print", "did you mean `println`?"),
            Map.entry("listOf", "write `List.of(...)`"), Map.entry("mutableListOf", "write `ArrayList<T>()`"),
            Map.entry("arrayListOf", "write `ArrayList<T>()`"), Map.entry("emptyList", "write `List.of()`"),
            Map.entry("emptyMap", "write `Map.of()`"), Map.entry("arrayOf", "write `List.of(...)`"),
            Map.entry("mapOf", "write `Map.of(...)`"), Map.entry("setOf", "write `Set.of(...)`"),
            Map.entry("require", "throw an `IllegalArgumentException`"), Map.entry("check", "throw an `IllegalStateException`"),
            Map.entry("len", "write `s.length()` for a string, `xs.size()` for a list"),
            Map.entry("str", "write `String.valueOf(x)`"));

    // Kotlin's library functions on strings and lists, and what Java's classes have instead.
    private static final Map<String, String> METHODS = Map.of("first", "write `getFirst()`", "last", "write `getLast()`",
            "isNullOrEmpty", "write `s == null || s.isEmpty()`", "toInt", "write `Integer.parseInt(s)`",
            "toDouble", "write `Double.parseDouble(s)`", "count", "write `size()`");

    // Classes of java.lang, which need no import and so are not in JDK_TYPES.
    private static final List<String> LANG_TYPES = List.of("StringBuilder", "Math", "Integer", "Character", "System",
            "Exception", "RuntimeException", "IllegalArgumentException", "IllegalStateException", "Thread", "Iterable",
            "Comparable", "Ok", "Err");

    private static final Set<String> JAVA_WORDS = Set.of("final", "var", "return", "public", "private", "static",
            "class", "new", "this", "null", "true", "false", "void", "else", "case", "default", "switch", "record",
            "interface", "enum", "import", "package", "while", "for", "if");

    private static final List<String> BUILTIN_TYPES = List.of("Int", "Long", "Double", "Boolean", "String", "Character",
            "Object", "Result");

    private static final List<String> WORDS = List.of("null", "true", "false", "this", "println");

    static final String NO_TYPE_PARAMETERS = "Axolotl functions and classes have no type parameters, so write the type itself";

    private static final Pattern WORD = Pattern.compile("(?<![\\w$.])[A-Za-z_]\\w*");

    private Names() {}

    static String unknownType(String symbol) {
        String hint = switch (symbol) {
            case "Any" -> "write `Object`";
            case "Bool" -> "write `Boolean`";
            case "Char" -> "a character is a `Character`";
            case "Unit" -> "leave out the result type of a function that returns nothing";
            case "string" -> "write `String`";
            case "number" -> "write `Int` or `Double`";
            default -> symbol.matches("[A-Z]") ? NO_TYPE_PARAMETERS
                    : ARRAY_TYPES.contains(symbol) ? "Axolotl uses lists, such as `List<Int>`"
                    : JDK_TYPES.containsKey(symbol) ? "add `import " + JDK_TYPES.get(symbol) + "." + symbol + "`"
                    : symbol.equalsIgnoreCase("println") ? "did you mean `println`?"
                    : similarType(symbol);
        };
        return "unknown type `" + symbol + "`; " + hint;
    }

    private static String similarType(String symbol) {
        List<String> types = Stream.of(BUILTIN_TYPES.stream(), LANG_TYPES.stream(), JDK_TYPES.keySet().stream().sorted())
                .flatMap(s -> s).toList();
        String similar = types.stream().filter(t -> t.equalsIgnoreCase(symbol)).findFirst().orElse(closest(symbol, types));
        return similar != null ? "did you mean `" + similar + "`?" : "check the spelling or add an import";
    }

    /** What Java writes for a Kotlin method, or null. */
    static String methodHint(String name) {
        return METHODS.get(name);
    }

    /** The candidate one or two letters away from the name, the nearest first, or null. */
    static String closest(String name, List<String> candidates) {
        int allowed = name.length() > 4 ? 2 : 1;
        return candidates.stream().filter(c -> !c.equals(name) && distance(name, c) <= allowed)
                .min(java.util.Comparator.comparingInt(c -> distance(name, c))).orElse(null);
    }

    /** `None`, `undefined` and `nul`: what other languages, or a slip, write for Axolotl's own words. */
    static String nameHint(String symbol) {
        String foreign = switch (symbol) {
            case "None", "undefined", "nil", "NULL" -> "write `null`";
            case "True" -> "write `true`";
            case "False" -> "write `false`";
            case "self" -> "write `this`";
            case "console" -> "print with `println(...)`";
            default -> null;
        };
        if (foreign != null || symbol.length() < 3) {
            return foreign;
        }
        return WORDS.stream().filter(w -> distance(symbol, w) == 1).findFirst().map(w -> "did you mean `" + w + "`?")
                .orElse(null);
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
        String hint = FUNCTIONS.get(name);
        boolean println = !name.equals("println") && distance(name, "println") <= 2;
        return hint == null && println ? "did you mean `println`?" : hint;
    }

    /**
     * A name of the generated file one or two letters away from the unknown one, or null. A name that
     * starts with a capital only matches one that differs in case.
     */
    static String similar(String name, String[] javaLines) {
        if (name.length() < 3) {
            return null;
        }
        boolean capital = Character.isUpperCase(name.charAt(0));
        for (String line : javaLines) {
            Matcher word = WORD.matcher(line.replaceAll("\"[^\"\\\\]*+(\\\\.[^\"\\\\]*+)*+\"", "\"\""));
            while (word.find()) {
                String candidate = word.group();
                boolean close = capital ? candidate.equalsIgnoreCase(name)
                        : distance(name, candidate) <= (name.length() > 4 ? 2 : 1);
                if (!candidate.equals(name) && !JAVA_WORDS.contains(candidate) && close) {
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
