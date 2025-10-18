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

    private static final Pattern CONSTRAINTS = Pattern.compile("equality constraints (.+)$");
    private static final Pattern METHOD = Pattern.compile("static (.+?) [\\w]+\\(.*\\) \\{$");

    private Messages() {}

    /**
     * @param javaLines the generated file
     * @param javaLine  1-based line of the diagnostic
     */
    static Translated translate(String message, String[] javaLines, int javaLine) {
        String text = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        if (text.contains("return Result.propagate(")) {
            Translated tryMismatch = tryErrorType(message, javaLines, javaLine);
            if (tryMismatch != null) {
                return tryMismatch;
            }
        }
        return new Translated(ErrorCode.JAVAC, message);
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
