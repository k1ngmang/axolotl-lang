package org.axl.javac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.axl.compiler.ErrorCode;

/** Rewrites javac messages that have a clearer explanation in Axolotl terms. */
final class Messages {

    record Translated(ErrorCode code, String message) {}

    private static final Pattern DEREFERENCE = Pattern.compile("^\\[NullAway] dereferenced expression '(.+?)' is @Nullable");
    private static final Pattern NULLAWAY_LINK = Pattern.compile(";? ?\\(see http://t\\.uber\\.com/nullaway ?\\)");
    private static final Pattern IMPORTED_CLASS = Pattern.compile("^import ([\\w.]+);$");
    private static final Pattern CONSTRAINTS = Pattern.compile("equality constraints (.+)$");
    private static final Pattern METHOD = Pattern.compile("static (.+?) [\\w]+\\(.*\\) \\{$");
    private static final Pattern NULLABLE_ARGUMENT = Pattern.compile("^\\[NullAway] passing @Nullable parameter '(.+?)' where @NonNull is required");
    private static final Pattern INCOMPATIBLE = Pattern.compile("^incompatible types: (.+) cannot be converted to (.+)$");
    private static final Pattern UNKNOWN = Pattern.compile("^cannot find symbol; symbol: (variable|method|class) (.+?); location: (.+)$");
    private static final Pattern NOT_APPLICABLE = Pattern.compile(
            "^method (\\w+) in \\w+ .+? cannot be applied to given types;+ required: (.+?); found: (.+?); reason");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass (\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("\\b(?:java\\.lang|axl\\.lang)\\.([A-Z]\\w*)");
    private static final Pattern PRIMITIVE = Pattern.compile("\\b(int|long|boolean|double|Integer)\\b");

    private Messages() {}

    /**
     * @param javaLines the generated file
     * @param javaLine  1-based line of the diagnostic
     */
    static Translated translate(String message, String[] javaLines, int javaLine) {
        String text = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        if (message.startsWith(StrictNullChecker.PREFIX)) {
            return new Translated(ErrorCode.STRICT_NULL, message.substring(StrictNullChecker.PREFIX.length()));
        }
        if (text.contains("return Result.propagate(")) {
            Translated tryMismatch = tryErrorType(message, javaLines, javaLine);
            if (tryMismatch != null) {
                return tryMismatch;
            }
        }
        Matcher dereference = DEREFERENCE.matcher(message);
        if (dereference.find()) {
            String value = dereference.group(1);
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "value `" + value
                    + "` may be null: use `?.`, `?:` or check `if (" + value + " != null)`");
        }
        Matcher nullableArgument = NULLABLE_ARGUMENT.matcher(message);
        if (nullableArgument.find()) {
            String value = nullableArgument.group(1);
            if (value.equals("null")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE,
                        "`null` is passed where null is not accepted; make the type nullable with `?`");
            }
            String subject = IDENTIFIER.matcher(value).matches() ? "`" + value + "`" : "the argument";
            String check = IDENTIFIER.matcher(value).matches() ? "check `if (" + value + " != null)`" : "a null check";
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, subject
                    + " may be null, but the parameter does not accept null: use `?:` or " + check);
        }
        if (message.startsWith("[NullAway] ")) {
            return new Translated(ErrorCode.JAVAC,
                    NULLAWAY_LINK.matcher(message.substring("[NullAway] ".length())).replaceAll(""));
        }
        Matcher imported = IMPORTED_CLASS.matcher(text.strip());
        if (imported.find() && message.startsWith("cannot find symbol; symbol: class ")) {
            String name = imported.group(1);
            return new Translated(ErrorCode.IMPORT_MEMBER, "`" + name + "` is not a type: for a function write `import fn "
                    + name + "`, for a value `import val " + name + "`");
        }
        return new Translated(ErrorCode.JAVAC, javacMessage(message, javaLines));
    }

    /** Plain javac errors, in Axolotl's words and type names. */
    private static String javacMessage(String message, String[] javaLines) {
        Matcher incompatible = INCOMPATIBLE.matcher(message);
        if (incompatible.find()) {
            return "type mismatch: expected `" + axlType(incompatible.group(2)) + "`, found `" + axlType(incompatible.group(1)) + "`";
        }
        if (message.equals("missing return statement")) {
            return "the function does not return a value on every path; add a `return` at the end";
        }
        Matcher notApplicable = NOT_APPLICABLE.matcher(message);
        if (notApplicable.find()) {
            return "wrong arguments for `" + notApplicable.group(1) + "`: expected (" + argumentList(notApplicable.group(2))
                    + "), got (" + argumentList(notApplicable.group(3)) + ")";
        }
        Matcher unknown = UNKNOWN.matcher(message);
        if (unknown.find()) {
            String symbol = axlType(unknown.group(2)).replace(",", ", ");
            String location = unknown.group(3);
            boolean inThisFile = location.equals("class " + generatedClass(javaLines));
            return switch (unknown.group(1)) {
                case "variable" -> inThisFile ? "unknown name `" + symbol + "`; check the spelling or add an import"
                        : memberOwner(location) + " has no field `" + symbol + "`";
                case "method" -> inThisFile ? "unknown function `" + symbol + "`; check the spelling or add `import fn`"
                        : memberOwner(location) + " has no method `" + symbol + "`";
                default -> "unknown type `" + symbol + "`; check the spelling or add an import";
            };
        }
        return message;
    }

    /** `variable list of type java.util.List<java.lang.Integer>` or `class app.Util`. */
    private static String memberOwner(String location) {
        int ofType = location.indexOf(" of type ");
        if (location.startsWith("variable ") && ofType > 0) {
            return "`" + location.substring("variable ".length(), ofType) + "` of type `"
                    + axlType(location.substring(ofType + " of type ".length())) + "`";
        }
        return "`" + axlType(location.substring(location.indexOf(' ') + 1)) + "`";
    }

    private static String argumentList(String javac) {
        if (javac.equals("no arguments")) {
            return "";
        }
        return String.join(", ", Arrays.stream(javac.split(",")).map(Messages::axlType).toList());
    }

    private static String axlType(String javaType) {
        String type = QUALIFIED_TYPE.matcher(javaType.strip()).replaceAll("$1");
        return PRIMITIVE.matcher(type).replaceAll(m -> switch (m.group(1)) {
            case "int", "Integer" -> "Int";
            default -> Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1);
        });
    }

    /** The qualified name of the class generated for the .axl file. */
    private static String generatedClass(String[] javaLines) {
        String pkg = "";
        for (String line : javaLines) {
            String l = line.strip();
            if (l.startsWith("package ")) {
                pkg = l.substring("package ".length(), l.length() - 1) + ".";
            }
            Matcher cls = CLASS_DECLARATION.matcher(l);
            if (cls.find()) {
                return pkg + cls.group(1);
            }
        }
        return "";
    }

    /** `E` of `Result.propagate` is invariant, so a different error type fails inference there. */
    private static Translated tryErrorType(String message, String[] javaLines, int javaLine) {
        Matcher constraints = CONSTRAINTS.matcher(message);
        String returnType = enclosingReturnType(javaLines, javaLine);
        if (!constraints.find() || returnType == null) {
            return null;
        }
        List<String> typeArgs = typeArguments(returnType);
        if (typeArgs.size() != 2) {
            return null;
        }
        String expected = simple(typeArgs.get(1));
        String actual = Arrays.stream(constraints.group(1).split(","))
                .map(Messages::simple).filter(t -> !t.equals(expected)).findFirst().orElse(null);
        if (actual == null) {
            return null;
        }
        return new Translated(ErrorCode.TRY_ERROR_TYPE, "`try`: the error is `" + actual
                + "`, but the function returns errors of type `" + expected + "`; convert it with `mapErr`");
    }

    private static String enclosingReturnType(String[] javaLines, int javaLine) {
        for (int i = Math.min(javaLine, javaLines.length) - 1; i >= 0; i--) {
            Matcher method = METHOD.matcher(javaLines[i].strip());
            if (method.find()) {
                return method.group(1);
            }
        }
        return null;
    }

    /** Top-level type arguments of `Name<A, B<C>>`. */
    private static List<String> typeArguments(String type) {
        int open = type.indexOf('<');
        List<String> args = new ArrayList<>();
        if (open < 0 || !type.endsWith(">")) {
            return args;
        }
        int depth = 0;
        int start = open + 1;
        for (int i = start; i < type.length() - 1; i++) {
            char c = type.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(type.substring(start, i).strip());
                start = i + 1;
            }
        }
        args.add(type.substring(start, type.length() - 1).strip());
        return args;
    }

    private static String simple(String type) {
        String t = type.strip();
        return t.startsWith("java.lang.") ? t.substring("java.lang.".length()) : t;
    }
}
