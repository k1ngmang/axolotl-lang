package org.axl.javac;

import java.util.ArrayList;
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
    private static final Pattern CONSTRAINTS = Pattern.compile("equality constraints:? (.+)$");
    private static final Pattern METHOD = Pattern.compile("static (.+?) [\\w]+\\(.*\\) \\{$");
    private static final Pattern NULLABLE_ARGUMENT = Pattern.compile("^\\[NullAway] passing @Nullable parameter '(.+?)' where @NonNull is required");
    private static final Pattern LAMBDA_RETURN = Pattern.compile("^incompatible types: bad return type in lambda expression; (.+) cannot be converted to (.+)$");
    private static final Pattern INCOMPATIBLE = Pattern.compile("^incompatible types: (.+) cannot be converted to (.+)$");
    private static final Pattern BOUNDS = Pattern.compile(
            "inference variable \\w+ has incompatible bounds; (?:equality constraints|upper bounds): (.+?); lower bounds: (.+)$");
    private static final Pattern UNKNOWN = Pattern.compile("^cannot find symbol; symbol: (variable|method|class) (.+?); location: (.+)$");
    private static final Pattern UNKNOWN_STATIC = Pattern.compile("^cannot find symbol; symbol: static (\\w+); location: \\w+ (.+)$");
    private static final Pattern NOT_APPLICABLE = Pattern.compile(
            "^method (\\w+) in \\w+ .+? cannot be applied to given types;+ required: (.+?); found: (.+?); reason");
    private static final Pattern NO_SUITABLE = Pattern.compile("^no suitable (?:method|constructor) found for (\\w+)\\((.*?)\\);");
    private static final Pattern CANDIDATE = Pattern.compile("(?:method|constructor) [\\w.$<>]+?\\.(?:<[^>]*>)?\\w+\\(([^)]*)\\) is not applicable");
    private static final Pattern BAD_METHOD_REFERENCE = Pattern.compile(
            "^invalid method reference; cannot find symbol; symbol: method (\\w+)\\(.*?\\); location: \\w+ (.+)$");
    private static final Pattern EQUALITY_AND_UPPER = Pattern.compile(
            "inference variable \\w+ has incompatible bounds; equality constraints: (.+?); upper bounds: (.+)$");
    // `it` of a mapResult loop and the translator's temporaries, which the author never wrote.
    private static final Pattern UNUSED_GENERATED = Pattern.compile("'(?:it|\\$\\w+)'");
    private static final Pattern NOT_DEREFERENCED = Pattern.compile("^(\\w+) cannot be dereferenced$");
    private static final Pattern BAD_OPERANDS = Pattern.compile("^bad operand types for binary operator '(.+?)'; first type: (.+?); second type: (.+)$");
    private static final Pattern DUPLICATE_METHOD = Pattern.compile("^method (\\w+)\\(.*\\) is already defined in class .+$");
    private static final Pattern VAR_INITIALIZER = Pattern.compile("^cannot infer type for local variable (\\w+); \\(variable initializer is '(void|null)'\\)$");
    private static final Pattern WRONG_TRY_ARGUMENT = Pattern.compile("^wrong arguments for `(?:propagate|unwrap)`: expected \\(.*\\), got \\((.+)\\)$");
    private static final Pattern UNREPORTED = Pattern.compile("^unreported exception ([\\w.$]+); must be caught or declared to be thrown$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass (\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("\\b(?:java\\.lang|axl\\.lang)\\.([A-Z]\\w*)");
    private static final Pattern NULLABLE_TYPE = Pattern.compile("@(?:org\\.jspecify\\.annotations\\.)?Nullable ([\\w.]+(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>)?)");
    private static final Pattern PRIMITIVE = Pattern.compile("\\b(int|long|boolean|double|float|short|byte|char|Integer|Character)\\b");
    private static final Pattern GENERATED = Pattern.compile("\\$(?:t|n|c|p|ex)\\d+|\\bNulls\\.|\\bResult\\.(?:unwrap|propagate|attempt)\\b|requireNonNull");
    private static final Pattern SUGGESTION = Pattern.compile(";? ?(?:Did you mean |\\(Note: ).*$");

    private Messages() {}

    /** Warnings about the shape of generated code, which the author cannot change. */
    static boolean ignored(String message) {
        return message.startsWith("[UnnecessaryParentheses]")
                || message.startsWith("[UnusedVariable]") && UNUSED_GENERATED.matcher(message).find();
    }

    /**
     * @param javaLines the generated file
     * @param javaLine  1-based line of the diagnostic
     */
    static Translated translate(String message, String[] javaLines, int javaLine) {
        Translated translated = rewrite(message, javaLines, javaLine);
        return new Translated(translated.code(), hideGenerated(translated.message()));
    }

    private static Translated rewrite(String message, String[] javaLines, int javaLine) {
        String text = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        if (message.startsWith(NullChecker.STRICT_PREFIX)) {
            return new Translated(ErrorCode.STRICT_NULL, message.substring(NullChecker.STRICT_PREFIX.length()));
        }
        if (message.startsWith(InitCycles.PREFIX)) {
            return new Translated(ErrorCode.INIT_ORDER, message.substring(InitCycles.PREFIX.length()));
        }
        if (message.startsWith(NullChecker.PREFIX)) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, message.substring(NullChecker.PREFIX.length()));
        }
        if (text.contains("Result.propagate(") || text.contains("Result.unwrap(") || text.contains("Result.<")) {
            Translated tryProblem = tryProblem(message, javaLines, javaLine);
            if (tryProblem != null) {
                return tryProblem;
            }
        }
        if (message.startsWith("[NullAway] ")) {
            return nullAway(message, text, javaLines);
        }
        if (message.startsWith("[ReturnValueIgnored] Return value of 'toList' must be used")) {
            return new Translated(ErrorCode.JAVAC, "the list this pipe builds is not used; end the pipe with `forEach`, "
                    + "or keep the list in a `val`");
        }
        Matcher imported = IMPORTED_CLASS.matcher(text.strip());
        if (imported.find() && message.startsWith("cannot find symbol; symbol: class ")) {
            String name = imported.group(1);
            String owner = name.substring(0, Math.max(0, name.lastIndexOf('.')));
            String ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
            if (ownerSimple.isEmpty() || !Character.isUpperCase(ownerSimple.charAt(0))) {
                return new Translated(ErrorCode.JAVAC, "unknown type `" + name + "`; check the spelling");
            }
            return new Translated(ErrorCode.IMPORT_MEMBER, "`" + name + "` is not a type: for a function write `import fn "
                    + name + "`, for a value `import val " + name + "`");
        }
        Matcher duplicate = DUPLICATE_METHOD.matcher(message);
        if (duplicate.find()) {
            return new Translated(ErrorCode.DUPLICATE_NAME, "`" + duplicate.group(1)
                    + "` is already declared with the same parameter types");
        }
        return new Translated(ErrorCode.JAVAC, SUGGESTION.matcher(javacMessage(message, text, javaLines)).replaceAll(""));
    }

    private static Translated nullAway(String message, String text, String[] javaLines) {
        Matcher dereference = DEREFERENCE.matcher(message);
        if (dereference.find()) {
            String value = dereference.group(1);
            if (GENERATED.matcher(value).find() && text.contains(value + ".isErr()")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "the `Result` given to `try` may be null; "
                        + "check it for null first or give a default with `?:`");
            }
            if (GENERATED.matcher(value).find()) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "this value may be null: use `?.`, `?:` or a null check");
            }
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
            boolean name = IDENTIFIER.matcher(value).matches() && !GENERATED.matcher(value).find();
            String subject = name ? "`" + value + "`" : "the argument";
            String check = name ? "check `if (" + value + " != null)`" : "a null check";
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, subject
                    + " may be null, but the parameter does not accept null: use `?:` or " + check);
        }
        String plain = NULLAWAY_LINK.matcher(message.substring("[NullAway] ".length())).replaceAll("");
        if (plain.startsWith("returning @Nullable expression from method with @NonNull return type")) {
            if (text.contains("Nulls.or(")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "the fallback of `?:` may be null, but the value "
                        + "is not nullable; give a fallback that is not null or add `?` to the type");
            }
            if (text.contains("Nulls.nonNull(")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`map` may produce null here, but the list it builds "
                        + "cannot hold nulls; filter them out first or give a default with `?:`");
            }
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "the returned value may be null, but the result type has "
                    + "no `?`; add `?` to the result type or return a value that is not null");
        }
        if (plain.startsWith("assigning @Nullable expression to @NonNull field")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "the value may be null, but the type of the value has no "
                    + "`?`; add `?` to its type or give a default with `?:`");
        }
        if (plain.startsWith("unboxing of a @Nullable value") || plain.startsWith("unboxing of a @Nullable expression")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE,
                    "a value that may be null is used as a number or boolean; give a default with `?:`");
        }
        if (plain.startsWith("incompatible types: ") && text.contains(">unwrap(")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`mapResult` may produce null values here, but the list "
                    + "it builds cannot hold nulls; add `?` to the element type or return values that are not null");
        }
        return new Translated(plain.startsWith("incompatible types: ") ? ErrorCode.NULLABLE_DEREFERENCE : ErrorCode.JAVAC,
                javacMessage(plain, text, javaLines));
    }

    /** Plain javac errors, in Axolotl's words and type names. */
    private static String javacMessage(String message, String text, String[] javaLines) {
        Matcher lambdaReturn = LAMBDA_RETURN.matcher(message);
        if (lambdaReturn.find()) {
            return "the lambda returns `" + axlType(lambdaReturn.group(1)) + "`, but `" + axlType(lambdaReturn.group(2))
                    + "` is expected";
        }
        boolean voidResult = message.contains("lambda body is not compatible with a void functional interface")
                || message.contains("void cannot be converted to") || message.contains("lower bounds: void");
        if (voidResult && text.contains("Nulls.map(")) {
            return "a lambda whose body is a `?.` call returns that call's value, but nothing is expected here; "
                    + "write it with a null check: `{ -> if (x != null) { x.m() } }`";
        }
        if (message.equals("incompatible types: incompatible parameter types in lambda expression")) {
            return "the lambda has the wrong number of parameters; for one parameter write `{ x -> ... }` or use `it`";
        }
        Matcher equalityAndUpper = EQUALITY_AND_UPPER.matcher(message);
        if (equalityAndUpper.find()) {
            String expected = splitTopLevel(equalityAndUpper.group(2)).stream().filter(t -> !t.equals("java.lang.Object"))
                    .findFirst().orElse(equalityAndUpper.group(2));
            return "type mismatch: expected `" + axlType(expected) + "`, found `" + axlType(equalityAndUpper.group(1)) + "`";
        }
        Matcher bounds = BOUNDS.matcher(message);
        if (bounds.find()) {
            String expected = splitTopLevel(bounds.group(1)).stream().filter(t -> !t.equals("java.lang.Object"))
                    .findFirst().orElse(bounds.group(1));
            return "type mismatch: expected `" + axlType(expected) + "`, found `" + axlType(splitTopLevel(bounds.group(2)).getFirst()) + "`";
        }
        if (message.startsWith("incompatible types: inferred type does not conform to upper bound(s)")) {
            return text.contains("Nulls.or(") ? "the two sides of `?:` have different types" : "the types in this expression do not fit together";
        }
        Matcher incompatible = INCOMPATIBLE.matcher(message);
        if (incompatible.find()) {
            return "type mismatch: expected `" + axlType(incompatible.group(2)) + "`, found `" + axlType(incompatible.group(1)) + "`";
        }
        if (message.equals("incompatible types: missing return value")) {
            return "this function returns a value; give `return` one";
        }
        Matcher badReference = BAD_METHOD_REFERENCE.matcher(message);
        if (badReference.find()) {
            return "`" + axlType(badReference.group(2)) + "` has no method `" + badReference.group(1) + "` for this reference";
        }
        if (message.startsWith("method main in class") || message.matches("^method main in \\w+ .+ cannot be applied.*")) {
            return "`main` starts the program and cannot be called; move its code into a function and call that";
        }
        if (message.equals("missing return statement")) {
            return "the function does not return a value on every path; add a `return` at the end";
        }
        Matcher notApplicable = NOT_APPLICABLE.matcher(message);
        if (notApplicable.find()) {
            return "wrong arguments for `" + notApplicable.group(1) + "`: expected (" + argumentList(notApplicable.group(2))
                    + "), got (" + argumentList(notApplicable.group(3)) + ")";
        }
        Matcher noSuitable = NO_SUITABLE.matcher(message);
        if (noSuitable.find()) {
            List<String> candidates = new ArrayList<>();
            Matcher candidate = CANDIDATE.matcher(message);
            while (candidate.find()) {
                candidates.add("(" + argumentList(candidate.group(1).isEmpty() ? "no arguments" : candidate.group(1)) + ")");
            }
            String got = noSuitable.group(2).isEmpty() ? "" : argumentList(noSuitable.group(2));
            String takes = candidates.size() > 4
                    ? String.join(", ", candidates.subList(0, 4)) + " and " + (candidates.size() - 4) + " more"
                    : String.join(", ", candidates);
            return "wrong arguments for `" + noSuitable.group(1) + "`: got (" + got + "); it takes " + takes;
        }
        Matcher unknownStatic = UNKNOWN_STATIC.matcher(message);
        if (unknownStatic.find()) {
            return "`" + axlType(unknownStatic.group(2)) + "` has no static member `" + unknownStatic.group(1) + "`";
        }
        Matcher unknown = UNKNOWN.matcher(message);
        if (unknown.find()) {
            String symbol = axlType(unknown.group(2));
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
        Matcher notDereferenced = NOT_DEREFERENCED.matcher(message);
        if (notDereferenced.find()) {
            return "`" + axlType(notDereferenced.group(1)) + "` is a primitive value and has no members";
        }
        Matcher badOperands = BAD_OPERANDS.matcher(message);
        if (badOperands.find()) {
            return "`" + badOperands.group(1) + "` cannot be applied to `" + axlType(badOperands.group(2)) + "` and `"
                    + axlType(badOperands.group(3)) + "`";
        }
        if (message.equals("'void' type not allowed here")) {
            return "this function returns nothing, so its result cannot be used as a value";
        }
        Matcher varInitializer = VAR_INITIALIZER.matcher(message);
        if (varInitializer.find()) {
            String name = varInitializer.group(1);
            return varInitializer.group(2).equals("void") ? "`" + name + "` cannot hold the result of a function that returns nothing"
                    : "`" + name + "` needs a type to hold `null`: `val " + name + ": Type? = null`";
        }
        if (message.equals("local variables referenced from a lambda expression must be final or effectively final")) {
            return "a lambda uses a `var` that changes; copy it to a `val` first";
        }
        Matcher unreported = UNREPORTED.matcher(message);
        if (unreported.find()) {
            return "this call can throw `" + axlType(unreported.group(1)) + "`, which Axolotl functions cannot pass on; "
                    + "wrap it in `attempt ... else ...` to turn it into a `Result`";
        }
        if (message.equals("illegal forward reference")) {
            return "a top-level value is used before it is declared; move its declaration up";
        }
        return message;
    }

    /** `try e` on something that is not a `Result`: javac complains about the code `try` expands to. */
    private static Translated tryProblem(String message, String[] javaLines, int javaLine) {
        Translated errorType = tryErrorType(message, javaLines, javaLine);
        if (errorType != null) {
            return errorType;
        }
        Matcher notDereferenced = NOT_DEREFERENCED.matcher(message);
        Matcher wrongArgument = WRONG_TRY_ARGUMENT.matcher(javacMessage(message, "", javaLines));
        String found = notDereferenced.find() ? notDereferenced.group(1) : wrongArgument.find() ? wrongArgument.group(1) : null;
        if (found == null) {
            return null;
        }
        return new Translated(ErrorCode.TRY_NOT_RESULT, "`try` needs a `Result`, found `" + axlType(found) + "`");
    }

    /** `E` of `Result.propagate` is invariant, so a different error type fails inference there. */
    private static Translated tryErrorType(String message, String[] javaLines, int javaLine) {
        Matcher constraints = CONSTRAINTS.matcher(message);
        String returnType = enclosingReturnType(javaLines, javaLine);
        if (!constraints.find() || returnType == null) {
            return null;
        }
        List<String> typeArgs = splitTopLevel(typeArgumentList(returnType));
        if (typeArgs.size() != 2) {
            return null;
        }
        String expected = simpleNames(axlType(typeArgs.get(1)));
        String actual = splitTopLevel(constraints.group(1)).stream()
                .map(t -> simpleNames(axlType(t))).filter(t -> !t.equals(expected)).findFirst().orElse(null);
        if (actual == null) {
            return null;
        }
        return new Translated(ErrorCode.TRY_ERROR_TYPE, "`try`: the error is `" + actual
                + "`, but the function returns errors of type `" + expected + "`; convert it with `mapErr`");
    }

    /** `java.util.Map<String, Int>` as `Map<String, Int>`, the way the function's own type is written. */
    private static String simpleNames(String type) {
        return type.replaceAll("\\b[a-z]\\w*(?:\\.[a-z]\\w*)*\\.(?=[A-Z])", "");
    }

    /** Messages must not show what the translator generated; say what it stands for instead. */
    private static String hideGenerated(String message) {
        String result = message.replaceAll("`\\$n\\d+` of type `", "the value of type `");
        Matcher quoted = Pattern.compile("`([^`]*)`|'([^']*)'").matcher(result);
        return quoted.replaceAll(m -> {
            String inner = m.group(1) != null ? m.group(1) : m.group(2);
            return GENERATED.matcher(inner).find() ? "this value" : Matcher.quoteReplacement(m.group());
        });
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
        return String.join(", ", splitTopLevel(javac).stream().map(Messages::axlType).toList());
    }

    private static String axlType(String javaType) {
        String type = NULLABLE_TYPE.matcher(javaType.strip()).replaceAll("$1?");
        type = simpleNames(QUALIFIED_TYPE.matcher(type).replaceAll("$1"));
        return PRIMITIVE.matcher(type).replaceAll(m -> switch (m.group(1)) {
            case "int", "Integer" -> "Int";
            case "char", "Character" -> "Char";
            default -> Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1);
        }).replace(",", ", ").replace(",  ", ", ");
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

    private static String enclosingReturnType(String[] javaLines, int javaLine) {
        for (int i = Math.min(javaLine, javaLines.length) - 1; i >= 0; i--) {
            Matcher method = METHOD.matcher(javaLines[i].strip());
            if (method.find()) {
                return method.group(1);
            }
        }
        return null;
    }

    /** `A, B<C>` of `Name<A, B<C>>`, or an empty string. */
    private static String typeArgumentList(String type) {
        int open = type.indexOf('<');
        return open < 0 || !type.endsWith(">") ? "" : type.substring(open + 1, type.length() - 1);
    }

    /** Splits at commas outside of angle brackets. */
    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(list.substring(start, i).strip());
                start = i + 1;
            }
        }
        if (!list.isBlank()) {
            parts.add(list.substring(start).strip());
        }
        return parts;
    }
}
