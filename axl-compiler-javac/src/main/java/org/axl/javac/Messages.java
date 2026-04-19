package org.axl.javac;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;

/** Rewrites javac messages that have a clearer explanation in Axolotl terms. */
final class Messages {

    record Translated(ErrorCode code, String message) {}

    private static final Pattern DEREFERENCE = Pattern.compile("^\\[NullAway] dereferenced expression '(.+?)' is @Nullable");
    private static final Pattern NULLAWAY_LINK = Pattern.compile(";? ?\\(see http://t\\.uber\\.com/nullaway ?\\)");
    private static final Pattern IMPORTED_CLASS = Pattern.compile("^import ([\\w.]+);$");
    private static final Pattern STATIC_IMPORT = Pattern.compile("^import static ([\\w.]+)\\.\\w+;$");
    private static final Pattern CONSTRAINTS = Pattern.compile("incompatible equality constraints (.+)$");
    private static final Pattern TRY_CHECK = Pattern.compile("^if \\((\\$t\\d+)\\.isErr\\(\\)\\) return Result\\.propagate\\(\\1\\);$");
    private static final Pattern NO_IS_ERR = Pattern.compile("^cannot find symbol; symbol: method isErr\\(\\); location: variable \\$t\\d+ of type (.+)$");
    private static final Pattern MAP_RESULT_ELEMENT = Pattern.compile(
            "^incompatible types: (?:axl\\.lang\\.)?Result<(.+?),.+> cannot be converted to (?:axl\\.lang\\.)?Result<(.+),\\?>$");
    private static final Pattern METHOD = Pattern.compile("static (.+?) [\\w]+\\(.*\\) \\{$");
    // javac reports a second assignment of a final field both ways, once from the plugin's own pass.
    private static final Pattern FINAL_FIELD = Pattern.compile(
            "^(?:cannot assign a value to final variable (\\w+)|variable (\\w+) might already have been assigned)$");
    private static final Pattern NULLABLE_SELECTOR = Pattern.compile("^switch selector expression '(.+?)' is @Nullable");
    private static final Pattern NULLABLE_ITERABLE = Pattern.compile("^enhanced-for expression '?(.+?)'? is @Nullable");
    private static final Pattern GENERATED_OR_SUBJECT = Pattern.compile("\\w*\\$e\\d+");
    private static final Pattern NULLABLE_ARGUMENT = Pattern.compile("^\\[NullAway] passing @Nullable parameter '(.+?)' where @NonNull is required");
    private static final Pattern LAMBDA_RETURN = Pattern.compile("^incompatible types: bad return type in lambda expression; (.+) cannot be converted to (.+)$");
    private static final Pattern NO_INSTANCE = Pattern.compile(
            "no instance\\(s\\) of type variable\\(s\\) [\\w,]+ exist so that ([\\w.$]+(?:<.*>)?) conforms to (.+)$");
    private static final Pattern LOSSY = Pattern.compile("^incompatible types: possible lossy conversion from (\\w+) to (\\w+)$");
    private static final Pattern INCOMPATIBLE = Pattern.compile(
            "^incompatible types: (?:bad type in conditional expression; )?(.+) cannot be converted to (.+?)(?: \\(.+ is a subtype of .+\\))?$");
    private static final Pattern BOUNDS = Pattern.compile(
            "inference variable \\w+ has incompatible bounds; (?:equality constraints|upper bounds): (.+?); lower bounds: (.+)$");
    private static final Pattern UNKNOWN = Pattern.compile("^cannot find symbol; symbol: (variable|method|class) (.+?); location: (.+)$");
    private static final Pattern UNKNOWN_STATIC = Pattern.compile("^cannot find symbol; symbol: static (\\w+); location: \\w+ (.+)$");
    private static final Pattern NOT_APPLICABLE = Pattern.compile(
            "^(?:method|constructor) (\\w+) in \\w+ .+? cannot be applied to given types;+ required: (.+?); found: (.+?); reason");
    private static final Pattern PRIVATE_ACCESS = Pattern.compile("^(\\w+)(\\(.*\\))? has private access in ([\\w.$]+)$");
    private static final Pattern NO_SUITABLE = Pattern.compile("^no suitable (?:method|constructor) found for (\\w+)\\((.*?)\\);");
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?:method|constructor) ([\\w.$<>]+?)\\.(?:<[^>]*>)?(\\w+)\\(([^)]*)\\) is not applicable");
    private static final Pattern BAD_METHOD_REFERENCE = Pattern.compile(
            "^invalid method reference; cannot find symbol; symbol: method (\\w+)\\(.*?\\); location: \\w+ (.+)$");
    private static final Pattern EQUALITY_AND_UPPER = Pattern.compile(
            "inference variable \\w+ has incompatible bounds; equality constraints: (.+?); upper bounds: (.+)$");
    // `it` of a mapResult loop and the translator's temporaries, which the author never wrote.
    private static final Pattern UNUSED_GENERATED = Pattern.compile("'(?:it|\\$\\w+|\\w+\\$[es]?\\d+)'");
    private static final Pattern IGNORED_RESULT = Pattern.compile("^\\[ReturnValueIgnored] Return value of '(\\w+)' must be used");
    private static final Pattern EQUALS_TYPES = Pattern.compile("^(\\[EqualsIncompatibleType] .*incompatible types )(.+) and (.+)$");
    private static final Pattern COLLECTION_TYPES = Pattern.compile(
            "^(\\[CollectionIncompatibleType] .*its type )(.+?)( is not compatible with its collection's type argument )(.+)$");
    private static final Pattern FORMAT_TYPE = Pattern.compile("'([\\w.$]+)' cannot be formatted");
    private static final Pattern ERROR_PRONE_LINK = Pattern.compile("\\.?;? ?\\(see https://errorprone\\.info/\\S+\\)");
    private static final Pattern NOT_DEREFERENCED = Pattern.compile("^(\\w+) cannot be dereferenced$");
    private static final Pattern BAD_OPERANDS = Pattern.compile("^bad operand types for binary operator '(.+?)'; first type: (.+?); second type: (.+)$");
    private static final Pattern DUPLICATE_METHOD = Pattern.compile("^method (\\w+)\\(.*\\) is already defined in class .+$");
    private static final Pattern NAME_CLASH = Pattern.compile("^name clash: (\\w+)\\((.*?)\\) and (\\w+)\\((.*)\\) have the same erasure$");
    private static final Pattern VAR_INITIALIZER = Pattern.compile("^cannot infer type for local variable (\\w+); \\(variable initializer is '(void|null)'\\)$");
    private static final Pattern WRONG_TRY_ARGUMENT = Pattern.compile("^wrong arguments for `(?:propagate|unwrap)`: expected \\(.*\\), got \\((.+)\\)$");
    private static final Pattern LAMBDA_INITIALIZER = Pattern.compile("^cannot infer type for local variable (\\w+); \\((?:lambda expression|method reference) needs an explicit target-type\\)$");
    private static final Pattern UNREPORTED = Pattern.compile("^unreported exception ([\\w.$]+); must be caught or declared to be thrown$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass (\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("\\b(?:java\\.lang|axl\\.lang)\\.([A-Z]\\w*)");
    private static final Pattern NULLABLE_TYPE = Pattern.compile("@(?:org\\.jspecify\\.annotations\\.)?Nullable ([\\w.]+(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>)?)");
    // Axolotl names only these; `float`, `short` and `byte` keep Java's names, since `Float` would mean the boxed class.
    private static final Pattern PRIMITIVE = Pattern.compile("\\b(int|long|boolean|double|char|Integer)\\b");
    private static final Pattern GENERATED = Pattern.compile("\\$(?:t|n|c|p|ex)\\d+|\\bNulls\\.|\\bResult\\.(?:unwrap|propagate|attempt)\\b|requireNonNull");
    private static final Pattern SUGGESTION = Pattern.compile(";? ?(?:Did you mean |\\(Note: ).*$");
    // `is` becomes a type pattern in a switch or after instanceof.
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\bcase [\\w.<>]+ \\w+(?:\\$\\d+)? ->|\\binstanceof\\b");
    private static final Pattern PRIMITIVE_ARGUMENT = Pattern.compile("[<,]\\s*(int|long|double|boolean|char)\\s*[,>]");
    private static final Pattern SELF_REFERENCE = Pattern.compile(
            "^cannot infer type for local variable (\\w+); \\(cannot use 'var' on self-referencing variable\\)$");
    private static final Pattern UNINITIALIZED = Pattern.compile("^variable (\\w+) might not have been initialized$");
    private static final Pattern NON_STATIC = Pattern.compile("^non-static (method|variable) (\\w+)(\\(.*\\))? cannot be referenced from a static context$");
    private static final Pattern FOR_EACH = Pattern.compile("^for-each not applicable to expression type; required: array or java\\.lang\\.Iterable; found: (.+)$");
    private static final Pattern UNARY = Pattern.compile("^bad operand type (.+) for unary operator '(.+)'$");
    private static final Pattern MISSING_METHOD = Pattern.compile(
            "^(\\w+) is not abstract and does not override abstract method (\\w+)\\((.*)\\) in ([\\w.$]+)$");
    private static final Pattern VOID_METHOD = Pattern.compile("\\bstatic void (\\w+)\\(|^\\s*public void (\\w+)\\(");
    private static final Pattern RECEIVER = Pattern.compile("\\b([A-Z]\\w*)\\.(\\w+)\\b");
    private static final Pattern USED_BEFORE = Pattern.compile("^`(\\w+)` is used before it has a value$");
    private static final Pattern ABSTRACT = Pattern.compile("^([\\w.$]+) is abstract; cannot be instantiated$");
    private static final Pattern DIVISION_BY_ZERO = Pattern.compile("[/%]\\s*0[lL]?\\b(?!\\.)");
    private static final Pattern IDENTITY = Pattern.compile("^\\[IdentityBinaryExpression] .*equivalent to `(.+?)`");
    private static final Pattern OBJECT_TO_STRING = Pattern.compile("^\\[ObjectToString] ([\\w.$]+) is final and does not override");
    private static final Pattern ELVIS_FALLBACK = Pattern.compile("\\?:\\s*(\\S)");
    private static final Pattern ATTEMPT_FALLBACK = Pattern.compile("\\belse\\s+(?:\\w+\\s*->\\s*)?(\\S)");
    private static final Pattern IMPORTED_NESTED = Pattern.compile("^import (?:[a-z]\\w*\\.)*([A-Z]\\w*)\\.([A-Z]\\w*);$");
    private static final Pattern IMPORTED_MEMBER = Pattern.compile("^`[\\w.]+` has no function or value `(\\w+)`$");
    private static final Pattern PIPE = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"|[\\w.()]+)\\s*\\|>");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    private static final Pattern TRY = Pattern.compile("\\btry\\b");
    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(\\w+)\\s*[-+*/%]?=(?!=)");
    // The messages of an import that failed, with the name it would have brought in.
    private static final Pattern FAILED_IMPORT = Pattern.compile("^(?:`[\\w.]+` has no function or value `(\\w+)`"
            + "|unknown type `(?:\\w+\\.)*(\\w+)`; check the spelling$|`(\\w+)` is declared in |`(\\w+)` is private to "
            + "|`[\\w.]+\\.(\\w+)` is not a type: )");
    private static final Pattern UNKNOWN_USE = Pattern.compile("^unknown (?:function|name|type) `(\\w+)");
    private static final String PIPE_SUBJECT = "`|>` needs a collection or a stream";
    private static final String SORTED_ARITY = "`sorted` takes a comparator with two parameters: "
            + "`sorted { a, b -> a.compareTo(b) }`";
    private static final Pattern RECORD = Pattern.compile("\\brecord (\\w+)(\\(.*\\))");
    private static final Pattern NEW_TYPE = Pattern.compile("\\bnew ([\\w.]+)(<[^(]*>)?\\(");

    private static final String LAMBDA_VALUE = "the lambda returns a value, but nothing is expected here; "
            + "use the value in a call, such as `println(...)`, or remove it";
    private static final String UNKNOWN_IMPORTED_CLASS = "` in this import; check the spelling";

    private Messages() {}

    /**
     * Where a message belongs in its .axl line. operator: at the operator javac points at, for messages about
     * an operator rather than the whole expression. anchor: the part of the line to use when javac points into
     * code generated for it, such as the lambda of `?:`; its group 1, if present, marks the exact place.
     * anchorFirst: the anchor is better than any place javac points at, which is code generated for it.
     */
    record Placement(boolean operator, Pattern anchor, boolean anchorFirst) {}

    static Placement placement(String translated, String javaText) {
        boolean operator = translated.contains("` cannot be applied to `") || translated.startsWith("[BoxedPrimitiveEquality]")
                || translated.startsWith("`==` tests whether") || translated.equals("division by zero")
                || translated.startsWith("both sides of this expression");
        Pattern anchor = anchor(translated, javaText);
        return new Placement(operator, anchor, anchor != null && anchor != TRY);
    }

    private static Pattern anchor(String translated, String javaText) {
        if (translated.startsWith(PIPE_SUBJECT)) {
            return PIPE;
        }
        Matcher member = IMPORTED_MEMBER.matcher(translated);
        if (member.find()) {
            return Pattern.compile("\\.(" + Pattern.quote(member.group(1)) + ")\\b");
        }
        if (translated.contains("` has no result type, so it cannot return a value")) {
            return RETURN;
        }
        if (!translated.startsWith("type mismatch")) {
            return null;
        }
        if (javaText.contains("Nulls.or(")) {
            return ELVIS_FALLBACK;
        }
        if (javaText.contains("Result.attempt(")) {
            return ATTEMPT_FALLBACK;
        }
        return javaText.contains("Result.unwrap(") || javaText.contains("Result.propagate(") ? TRY : null;
    }

    /** Whether the error only follows from another error of the same file, which already explains the mistake. */
    static boolean followsFrom(AxlDiagnostic effect, List<AxlDiagnostic> all) {
        return all.stream().anyMatch(cause -> cause != effect && cause.isError() && cause.file().equals(effect.file())
                && causes(cause, effect));
    }

    private static boolean causes(AxlDiagnostic cause, AxlDiagnostic effect) {
        boolean sameLine = cause.line() == effect.line() && cause.column() <= effect.column();
        if (sameLine && (cause.message().startsWith(PIPE_SUBJECT) || cause.message().equals(SORTED_ARITY))) {
            return true;
        }
        if (effect.message().startsWith("a function that returns nothing has no result type; remove `-> Unit`")) {
            return cause.message().startsWith("unknown type `Unit`");
        }
        Matcher usedBefore = USED_BEFORE.matcher(effect.message());
        if (usedBefore.find()) {
            return cause.message().startsWith("`" + usedBefore.group(1) + "` is used in its own value");
        }
        Matcher failedImport = FAILED_IMPORT.matcher(cause.message());
        Matcher unknownUse = UNKNOWN_USE.matcher(effect.message());
        if (cause.line() >= effect.line() || !failedImport.find() || !unknownUse.find()) {
            return false;
        }
        for (int group = 1; group <= failedImport.groupCount(); group++) {
            if (failedImport.group(group) != null) {
                return failedImport.group(group).equals(unknownUse.group(1));
            }
        }
        return false;
    }

    /** `s.length` for `s.length()`: a method of a JDK class without its parentheses. */
    private static String methodHint(String location, String name) {
        int ofType = location.indexOf(" of type ");
        String type = (ofType > 0 ? location.substring(ofType + " of type ".length()) : location.substring(location.indexOf(' ') + 1))
                .replaceAll("<.*", "");
        try {
            boolean method = Arrays.stream(Class.forName(type, false, Messages.class.getClassLoader()).getMethods())
                    .anyMatch(m -> m.getName().equals(name) && m.getParameterCount() == 0);
            return method ? "; `" + name + "` is a method: `" + name + "()`" : "";
        } catch (ClassNotFoundException | LinkageError e) {
            return "";
        }
    }

    /** `nmae` for `name`: a name of the generated file one or two letters away. */
    private static String unknownName(String symbol, String[] javaLines) {
        String hint = Names.typeHint(symbol);
        if (hint != null) {
            return "unknown name `" + symbol + "`; " + hint;
        }
        String similar = Names.similar(symbol, javaLines);
        return "unknown name `" + symbol + "`; " + (similar != null ? "did you mean `" + similar + "`?"
                : "check the spelling or add an import");
    }

    private static String unknownFunction(String symbol, String name, String[] javaLines) {
        String hint = Names.functionHint(name);
        String similar = hint == null ? Names.similar(name, javaLines) : null;
        return "unknown function `" + symbol + "`; " + (hint != null ? hint : similar != null ? "did you mean `" + similar + "`?"
                : "check the spelling or add `import fn`");
    }

    /** Messages that javac reports twice for one mistake, at different columns of the same line. */
    static boolean oncePerLine(String translated) {
        return translated.equals(LAMBDA_VALUE) || translated.endsWith(UNKNOWN_IMPORTED_CLASS)
                || translated.endsWith(Names.NO_TYPE_PARAMETERS);
    }

    /** Warnings about the shape of generated code, which the author cannot change. */
    static boolean ignored(String message) {
        // javac reports an unknown type a second time when it is thrown.
        return message.startsWith("unreported exception <any>;")
                || message.startsWith("[UnnecessaryParentheses]") || message.startsWith("[RefactorSwitch]")
                || message.startsWith("[StatementSwitchToExpressionSwitch]")
                // Axolotl has no @Override; the generated methods cannot carry one the author could write.
                || message.startsWith("[MissingOverride]")
                || message.startsWith("[UnusedVariable]") && UNUSED_GENERATED.matcher(message).find();
    }

    /**
     * @param javaLines the generated file
     * @param javaLine  1-based line of the diagnostic
     */
    static Translated translate(String message, String[] javaLines, int javaLine) {
        Translated translated = rewrite(withoutFileClass(message, javaLines), javaLines, javaLine);
        return new Translated(translated.code(), hideGenerated(translated.message()));
    }

    private static final Pattern NESTED_TYPE = Pattern.compile("^\\s*(?:(?:public|private|sealed|non-sealed|static|final)\\s+)*"
            + "(?:record|interface|class|enum)\\s+(\\w+)");

    private static List<String> nestedTypes(String[] javaLines) {
        List<String> names = new ArrayList<>();
        for (String line : javaLines) {
            Matcher nested = NESTED_TYPE.matcher(line);
            if (line.startsWith("    ") && !line.startsWith("     ") && nested.find()) {
                names.add(nested.group(1));
            }
        }
        return names;
    }

    /** The data classes and interfaces of a file are nested in its class, but written without it. */
    private static String withoutFileClass(String message, String[] javaLines) {
        String generated = generatedClass(javaLines);
        String fileClass = generated.substring(generated.lastIndexOf('.') + 1);
        String result = message;
        for (String line : javaLines) {
            Matcher nested = NESTED_TYPE.matcher(line);
            if (line.startsWith("    ") && !line.startsWith("     ") && nested.find() && !nested.group(1).equals(fileClass)) {
                result = result.replaceAll("\\b(?:[a-z]\\w*\\.)*" + Pattern.quote(fileClass) + "\\." + nested.group(1) + "\\b",
                        nested.group(1));
            }
            // A type of another file is written as imported: `Circle` for `import model.Shapes.Circle`.
            Matcher imported = IMPORTED_NESTED.matcher(line);
            if (imported.find()) {
                result = result.replaceAll("\\b(?:[a-z]\\w*\\.)*" + Pattern.quote(imported.group(1)) + "\\."
                        + imported.group(2) + "\\b", imported.group(2));
            }
        }
        return result;
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
            Translated tryProblem = tryProblem(message, text, javaLines, javaLine);
            if (tryProblem != null) {
                return tryProblem;
            }
        }
        String returnType = enclosingReturnType(javaLines, javaLine);
        if (message.equals("missing return statement") && ("Void".equals(returnType) || "Unit".equals(returnType))) {
            return new Translated(ErrorCode.JAVAC, "a function that returns nothing has no result type; remove `-> "
                    + returnType + "`");
        }
        if (message.startsWith("[NullAway] ")) {
            return nullAway(message, text, javaLines);
        }
        if (message.equals("constant string too long")) {
            // Java joins constant strings at compile time, so `+` does not get past the limit.
            return new Translated(ErrorCode.JAVAC, "this string is too long for Java, which allows 65535 bytes of UTF-8 "
                    + "in a string constant, also when joined with `+`; read long text from a file instead");
        }
        Matcher finalField = FINAL_FIELD.matcher(message);
        if (finalField.find()) {
            String name = finalField.group(1) != null ? finalField.group(1) : finalField.group(2);
            String dataClass = dataClassWith(javaLines, name);
            if (dataClass != null && text.contains("." + name + " ")) {
                return new Translated(ErrorCode.ASSIGN_TO_VAL, "a data class cannot change; create a new `" + dataClass
                        + "` with the new value of `" + name + "`");
            }
            return new Translated(ErrorCode.ASSIGN_TO_VAL, "cannot assign to the `val` property `" + name + "`; declare it with `var`");
        }
        if (message.startsWith("[ReturnValueIgnored] Return value of 'toList' must be used")) {
            return new Translated(ErrorCode.JAVAC, "the list this pipe builds is not used; end the pipe with `forEach`, "
                    + "or keep the list in a `val`");
        }
        Matcher ignoredResult = IGNORED_RESULT.matcher(message);
        if (ignoredResult.find()) {
            return new Translated(ErrorCode.JAVAC, "the result of `" + ignoredResult.group(1)
                    + "()` is not used; keep it in a `val` or remove the call");
        }
        if (message.startsWith("[")) {
            String plain = SUGGESTION.matcher(ERROR_PRONE_LINK.matcher(message).replaceAll("")).replaceAll("");
            return new Translated(ErrorCode.JAVAC, errorProne(plain, text));
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
        Matcher staticImport = STATIC_IMPORT.matcher(text.strip());
        if (staticImport.find() && (message.equals("static import only from classes and interfaces")
                || message.startsWith("cannot find symbol; symbol: class "))) {
            return new Translated(ErrorCode.JAVAC, "unknown class `" + staticImport.group(1) + UNKNOWN_IMPORTED_CLASS);
        }
        Matcher duplicate = DUPLICATE_METHOD.matcher(message);
        if (duplicate.find()) {
            return new Translated(ErrorCode.DUPLICATE_NAME, "`" + duplicate.group(1)
                    + "` is already declared with the same parameter types");
        }
        Matcher clash = NAME_CLASH.matcher(message);
        if (clash.find()) {
            return new Translated(ErrorCode.DUPLICATE_NAME, "`" + clash.group(1) + "(" + argumentList(clash.group(2)) + ")` and `"
                    + clash.group(3) + "(" + argumentList(clash.group(4)) + ")` differ only in type arguments, which Java "
                    + "does not keep at run time; give one of them another name");
        }
        return new Translated(ErrorCode.JAVAC, SUGGESTION.matcher(javacMessage(message, text, javaLines)).replaceAll(""));
    }

    /** Error Prone findings, with Axolotl's names for types and declarations. */
    private static String errorProne(String message, String text) {
        String reworded = errorProneReworded(message, text);
        if (reworded != null) {
            return reworded;
        }
        if (message.startsWith("[BoxedPrimitiveEquality]")) {
            return "[BoxedPrimitiveEquality] `==` compares `Int?` values, and numbers taken from collections, as objects "
                    + "rather than by value; use `java.util.Objects.equals(a, b)`";
        }
        if (message.startsWith("[ReturnAtTheEndOfVoidFunction]")) {
            return "[ReturnAtTheEndOfVoidFunction] the `return` at the end of this function is unnecessary";
        }
        Matcher equalsTypes = EQUALS_TYPES.matcher(message);
        if (equalsTypes.find()) {
            return equalsTypes.group(1) + axlType(equalsTypes.group(2)) + " and " + axlType(equalsTypes.group(3));
        }
        Matcher collectionTypes = COLLECTION_TYPES.matcher(message);
        if (collectionTypes.find()) {
            return collectionTypes.group(1) + axlType(collectionTypes.group(2)) + collectionTypes.group(3)
                    + axlType(collectionTypes.group(4));
        }
        Matcher formatType = FORMAT_TYPE.matcher(message);
        if (formatType.find()) {
            return formatType.replaceFirst(m -> Matcher.quoteReplacement("'" + axlType(m.group(1)) + "' cannot be formatted"));
        }
        return message.replaceFirst("^\\[UnusedVariable] The (?:local variable|parameter|field) '(\\w+)' is never read",
                "`$1` is never read").replaceFirst("^\\[UnusedMethod] Method '(\\w+)' is never used", "`$1` is never used");
    }

    /** Findings whose Java words, such as override and reference equality, have plain Axolotl advice. */
    private static String errorProneReworded(String message, String text) {
        if (message.startsWith("[ReferenceEquality]")) {
            return "`==` tests whether both sides are the same object, not whether they are equal; compare them with "
                    + "`a.equals(b)`";
        }
        if (message.startsWith("[ConstantOverflow]") && DIVISION_BY_ZERO.matcher(text).find()) {
            return "division by zero";
        }
        if (message.startsWith("[ConstantOverflow]")) {
            return "this calculation is too large for its type; use `Long`, with `L` after a number";
        }
        Matcher identity = IDENTITY.matcher(message);
        if (identity.find()) {
            return "both sides of this expression are the same, so its value is always `" + identity.group(1) + "`";
        }
        if (message.startsWith("[EqualsHashCode]")) {
            return "a class that gives `fn equals` must also give `fn hashCode() -> Int`, so that equal objects have "
                    + "equal hash codes";
        }
        Matcher toString = OBJECT_TO_STRING.matcher(message);
        if (toString.find()) {
            String type = simpleNames(axlType(toString.group(1)));
            return "`" + type + "` has no `toString`, so it is shown as `" + type + "@4488aabb`; give it "
                    + "`fn toString() -> String`";
        }
        return null;
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
        Matcher selector = NULLABLE_SELECTOR.matcher(plain);
        if (selector.find()) {
            String subject = GENERATED_OR_SUBJECT.matcher(selector.group(1)).matches() ? "the subject of this `when`"
                    : "`" + selector.group(1) + "`";
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, subject + " may be null, but no branch takes null; "
                    + "add a `null ->` branch or check it for null first");
        }
        Matcher iterated = NULLABLE_ITERABLE.matcher(plain);
        if (iterated.find()) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`" + iterated.group(1) + "` may be null, but `for` "
                    + "needs something to go through; use `?:`, a null check or `!!`");
        }
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
        // javac reports a value where nothing is expected twice, once as a missing return value.
        boolean missingReturnValue = message.equals("incompatible types: bad return type in lambda expression; missing return value");
        if (missingReturnValue && text.strip().equals("return;")) {
            return "this lambda must return a value; give `return` one";
        }
        if (missingReturnValue || message.startsWith("incompatible types: lambda body is not compatible with a void functional interface")) {
            return LAMBDA_VALUE;
        }
        if (voidResult && text.contains("Result.attempt(")) {
            return "`attempt` needs a call that returns a value; this one returns nothing";
        }
        if (text.contains("Nulls.or(") && message.endsWith(" is not a functional interface)")) {
            return "the right side of `?:` returns nothing, so it cannot be the value when the left side is null";
        }
        if (message.equals("incompatible types: incompatible parameter types in lambda expression") && text.contains(".sorted(")) {
            return SORTED_ARITY;
        }
        if (message.equals("incompatible types: incompatible parameter types in lambda expression")) {
            return "the lambda has the wrong number of parameters; for one parameter write `{ x -> ... }` or use `it`";
        }
        Matcher equalityAndUpper = EQUALITY_AND_UPPER.matcher(message);
        if (equalityAndUpper.find()) {
            String expected = splitTopLevel(equalityAndUpper.group(2)).stream().filter(t -> !t.equals("java.lang.Object"))
                    .findFirst().orElse(equalityAndUpper.group(2));
            return mismatch(expected, equalityAndUpper.group(1));
        }
        Matcher bounds = BOUNDS.matcher(message);
        if (bounds.find()) {
            String expected = splitTopLevel(bounds.group(1)).stream().filter(t -> !t.equals("java.lang.Object"))
                    .findFirst().orElse(bounds.group(1));
            return mismatch(expected, splitTopLevel(bounds.group(2)).getFirst());
        }
        if (message.startsWith("incompatible types: inferred type does not conform to upper bound(s)")) {
            return text.contains("Nulls.or(") ? "the two sides of `?:` have different types" : "the types in this expression do not fit together";
        }
        Matcher noInstance = NO_INSTANCE.matcher(message);
        if (noInstance.find()) {
            String found = axlType(noInstance.group(1).replaceFirst("<.*", ""));
            String expected = axlType(noInstance.group(2));
            return found.equals("Result") && text.contains("Result.attempt(")
                    ? "`attempt` gives a `Result`, but `" + expected + "` is expected"
                    : "type mismatch: expected `" + expected + "`, found `" + found + "`";
        }
        Matcher lossy = LOSSY.matcher(message);
        if (lossy.find()) {
            return lossyConversion(lossy.group(1), lossy.group(2));
        }
        Matcher incompatible = INCOMPATIBLE.matcher(message);
        if (incompatible.find() && TYPE_PATTERN.matcher(text).find()) {
            return "a value of type `" + axlType(incompatible.group(1)) + "` can never be `" + axlType(incompatible.group(2))
                    + "`; remove the `is` test";
        }
        if (incompatible.find(0) && incompatible.group(1).equals("void")) {
            return "this gives no value: the call returns nothing, but `" + axlType(incompatible.group(2)) + "` is expected";
        }
        if (incompatible.find(0)) {
            return mismatch(incompatible.group(2), incompatible.group(1));
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
        if (message.equals("not a statement")) {
            return "this expression does nothing on its own; use its value, for example in a `val`, or remove it";
        }
        if (message.matches("the switch (statement|expression) does not cover all possible input values")) {
            boolean sealed = Arrays.stream(javaLines).anyMatch(l -> l.contains("sealed interface "));
            return "this `when` does not cover every case; add an `else ->` branch"
                    + (sealed ? ", or a branch for each class of the sealed interface" : "");
        }
        if (message.equals("this case label is dominated by a preceding case label")) {
            return "this branch is never chosen: an earlier branch already matches everything it matches";
        }
        if (message.equals("switch has both an unconditional pattern and a default label")) {
            return "this `else` is never chosen: an earlier branch matches every value";
        }
        if (message.matches("duplicate case label")) {
            return "this value is already handled by an earlier branch of the `when`";
        }
        if (message.equals("missing return statement")) {
            return "the function does not return a value on every path; add a `return` at the end";
        }
        Matcher notApplicable = NOT_APPLICABLE.matcher(message);
        if (notApplicable.find()) {
            return "wrong arguments for `" + notApplicable.group(1) + "`: expected (" + argumentList(notApplicable.group(2))
                    + "), got (" + argumentList(notApplicable.group(3)) + ")";
        }
        Matcher privateAccess = PRIVATE_ACCESS.matcher(message);
        if (privateAccess.find()) {
            String name = privateAccess.group(1);
            String owner = simpleNames(axlType(privateAccess.group(3)));
            boolean constructor = privateAccess.group(2) != null && (owner.equals(name) || owner.endsWith("." + name));
            return constructor ? "`" + owner + "` cannot be created here: its constructor is private"
                    : "`" + name + "` is private to `" + owner + "` and can only be used in that file";
        }
        Matcher noSuitable = NO_SUITABLE.matcher(message);
        if (noSuitable.find()) {
            List<String> candidates = new ArrayList<>();
            Matcher candidate = CANDIDATE.matcher(message);
            while (candidate.find()) {
                String parameters = candidate.group(3);
                if (!isPrivate(candidate.group(1), candidate.group(2), parameters)) {
                    candidates.add("(" + argumentList(parameters.isEmpty() ? "no arguments" : parameters) + ")");
                }
            }
            String got = noSuitable.group(2).isEmpty() ? "" : argumentList(noSuitable.group(2));
            String takes = candidates.size() > 4
                    ? String.join(", ", candidates.subList(0, 4)) + " and " + (candidates.size() - 4) + " more"
                    : String.join(", ", candidates);
            return "wrong arguments for `" + noSuitable.group(1) + "`: got (" + got + "); it takes " + takes;
        }
        Matcher unknownStatic = UNKNOWN_STATIC.matcher(message);
        if (unknownStatic.find()) {
            return "`" + simpleNames(axlType(unknownStatic.group(2))) + "` has no function or value `" + unknownStatic.group(1) + "`";
        }
        Matcher unknown = UNKNOWN.matcher(message);
        if (unknown.find()) {
            String symbol = axlType(unknown.group(2));
            String location = unknown.group(3);
            // A name written alone in a method of a class of the file is looked for in that class first.
            String name = symbol.contains("(") ? symbol.substring(0, symbol.indexOf('(')) : symbol;
            boolean alone = !text.contains("." + name);
            boolean inThisFile = location.equals("class " + generatedClass(javaLines))
                    || alone && location.startsWith("class ") && nestedTypes(javaLines).contains(location.substring("class ".length()));
            if (symbol.equals("stream()") && text.contains(".stream().")) {
                int ofType = location.indexOf(" of type ");
                String type = ofType > 0 ? location.substring(ofType + " of type ".length()) : location.substring(location.indexOf(' ') + 1);
                return PIPE_SUBJECT + ", found `" + axlType(type) + "`";
            }
            return switch (unknown.group(1)) {
                case "variable" -> inThisFile ? unknownName(symbol, javaLines)
                        : memberOwner(location) + " has no field `" + symbol + "`" + methodHint(location, symbol);
                case "method" -> inThisFile ? unknownFunction(symbol, name, javaLines)
                        : memberOwner(location) + " has no method `" + withoutLambdas(symbol) + "`";
                default -> Names.unknownType(symbol);
            };
        }
        Matcher notDereferenced = NOT_DEREFERENCED.matcher(message);
        if (notDereferenced.find() && text.contains(".stream().")) {
            return PIPE_SUBJECT + ", found `" + axlType(notDereferenced.group(1)) + "`";
        }
        if (notDereferenced.find(0)) {
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
        Matcher lambdaInitializer = LAMBDA_INITIALIZER.matcher(message);
        if (lambdaInitializer.find()) {
            return "write the type of `" + lambdaInitializer.group(1) + "`, since a lambda takes it from there: "
                    + "`val " + lambdaInitializer.group(1) + ": Function<String, Int> = ...`";
        }
        if (message.startsWith("unexpected type; required: reference; found: ")) {
            String javaType = message.substring("unexpected type; required: reference; found: ".length());
            Matcher argument = PRIMITIVE_ARGUMENT.matcher(text);
            if (argument.find() && argument.group(1).equals(javaType)) {
                return "a type argument cannot be `" + javaType + "`; write `" + axlType(javaType) + "`, as in `List<"
                        + axlType(javaType) + ">`";
            }
            return "a value of type `" + axlType(javaType) + "` can never be another type; remove the `is` test";
        }
        Matcher varInitializer = VAR_INITIALIZER.matcher(message);
        if (varInitializer.find()) {
            String name = varInitializer.group(1);
            return varInitializer.group(2).equals("void") ? "`" + name + "` cannot hold the result of a function that returns nothing"
                    : "`" + name + "` needs a type to hold `null`: `val " + name + ": Type? = null`";
        }
        Matcher assignment = ASSIGNMENT.matcher(text);
        if (message.equals("local variables referenced from a lambda expression must be final or effectively final")
                && assignment.find()) {
            return "a lambda cannot change `" + assignment.group(1) + "`; use a `for` loop, or compute the value with a "
                    + "pipe, such as `|> count`";
        }
        if (message.equals("local variables referenced from a lambda expression must be final or effectively final")) {
            return "a lambda uses a `var` that changes; copy it to a `val` first";
        }
        Matcher unreported = UNREPORTED.matcher(message);
        if (unreported.find() && text.strip().startsWith("throw ")) {
            return "`" + axlType(unreported.group(1)) + "` is a checked exception and cannot be thrown here; throw a "
                    + "`RuntimeException`, such as `IllegalStateException`, or return an `Err`";
        }
        if (unreported.find(0)) {
            String throwsIt = "this call can throw `" + axlType(unreported.group(1)) + "`";
            // Inside `attempt` the call is already wrapped; javac only gives up on it because the call returns nothing.
            return text.contains("Result.attempt(") ? throwsIt + "; `attempt` catches it only around a call that returns a value"
                    : throwsIt + ", which Axolotl functions cannot pass on; wrap it in `attempt ... else ...` to turn it into a `Result`";
        }
        if (message.equals("illegal forward reference")) {
            return "a top-level value is used before it is declared; move its declaration up";
        }
        String structural = structuralMessage(message, text, javaLines);
        return structural != null ? structural : message;
    }

    /** Errors about what a class, enum or function is: static members, abstract types, missing methods. */
    private static String structuralMessage(String message, String text, String[] javaLines) {
        Matcher selfReference = SELF_REFERENCE.matcher(message);
        if (selfReference.find()) {
            return selfReference(selfReference.group(1));
        }
        Matcher uninitialized = UNINITIALIZED.matcher(message);
        if (uninitialized.find()) {
            return "`" + uninitialized.group(1) + "` is used before it has a value";
        }
        if (message.equals("incompatible types: unexpected return value")) {
            String function = enclosingVoidFunction(javaLines, text);
            return function == null ? "nothing is expected here, so `return` cannot give a value"
                    : "`" + function + "` has no result type, so it cannot return a value; add one after `->`, as in `fn " + function
                    + "(...) -> Int`";
        }
        Matcher nonStatic = NON_STATIC.matcher(message);
        if (nonStatic.find()) {
            return nonStatic(nonStatic.group(1), nonStatic.group(2), text);
        }
        Matcher forEach = FOR_EACH.matcher(message);
        if (forEach.find()) {
            String type = axlType(forEach.group(1));
            return "`for` needs a list or another collection, found `" + type + "`"
                    + (type.equals("String") ? "; for the characters of a string, use `s.toCharArray()`" : "");
        }
        Matcher unary = UNARY.matcher(message);
        if (unary.find()) {
            return "`" + unary.group(2) + "` cannot be applied to `" + axlType(unary.group(1)) + "`";
        }
        Matcher newType = NEW_TYPE.matcher(text);
        if (message.equals("enum classes may not be instantiated") && newType.find()) {
            String name = simpleNames(newType.group(1));
            return "`" + name + "` is an enum and cannot be created; use one of its constants, such as `" + name + "."
                    + firstConstant(javaLines, name) + "`";
        }
        Matcher missingMethod = MISSING_METHOD.matcher(message);
        if (missingMethod.find()) {
            String parameters = missingMethod.group(3).isEmpty() ? "" : "...";
            return "`" + missingMethod.group(1) + "` must give `fn " + missingMethod.group(2) + "(" + parameters
                    + ")`, which `" + simpleNames(axlType(missingMethod.group(4))) + "` requires";
        }
        Matcher abstractType = ABSTRACT.matcher(message);
        if (abstractType.find()) {
            return abstractType(abstractType.group(1), text, javaLines);
        }
        if (message.equals("interface expected here")) {
            return "a class cannot extend another class; it can only implement interfaces";
        }
        return null;
    }

    /** `List<String>()`: an interface or abstract class cannot be created, a class that implements it can. */
    private static String abstractType(String javaName, String text, String[] javaLines) {
        String name = simpleNames(axlType(javaName));
        String simple = name.substring(name.lastIndexOf('.') + 1);
        Matcher created = NEW_TYPE.matcher(text);
        String typeArguments = created.find() && created.group(2) != null ? axlType(created.group(2)) : "";
        String implementation = switch (simple) {
            case "List", "Collection", "Iterable" -> "ArrayList";
            case "Map" -> "HashMap";
            case "Set" -> "HashSet";
            case "Queue", "Deque" -> "ArrayDeque";
            default -> null;
        };
        String suggestion = implementation == null ? "create a class that implements it"
                : "create a class that implements it, such as `" + implementation + typeArguments + "()`";
        return isInterface(javaName, simple, javaLines) ? "`" + name + "` is an interface and cannot be created; " + suggestion
                : "`" + name + "` is abstract and cannot be created; use a class that extends it";
    }

    private static boolean isInterface(String javaName, String simple, String[] javaLines) {
        if (Arrays.stream(javaLines).anyMatch(l -> l.matches(".*\\binterface " + Pattern.quote(simple) + "\\b.*"))) {
            return true;
        }
        try {
            return Class.forName(javaName, false, Messages.class.getClassLoader()).isInterface();
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String selfReference(String name) {
        return "`" + name + "` is used in its own value; a line that ends with an operator goes on to the next line";
    }

    /** `String.length()` and `Box.v`: a member of each object, used on the class. */
    private static String nonStatic(String kind, String name, String text) {
        if (name.equals("this")) {
            return "`this` can only be used inside a class";
        }
        String member = kind.equals("method") ? name + "()" : name;
        Matcher receiver = RECEIVER.matcher(text);
        while (receiver.find()) {
            if (receiver.group(2).equals(name)) {
                String type = receiver.group(1);
                String value = Character.toLowerCase(type.charAt(0)) + type.substring(1);
                return "`" + member + "` belongs to each `" + type + "`, not to the class; use it on a value, such as `"
                        + value + "." + member + "`";
            }
        }
        return "`" + member + "` belongs to each object of its class; use it on a value";
    }

    /** The function a `return` is in, unless a lambda is in between. */
    private static String enclosingVoidFunction(String[] javaLines, String text) {
        for (int i = javaLines.length - 1; i >= 0; i--) {
            if (!javaLines[i].equals(text)) {
                continue;
            }
            for (int j = i - 1; j >= 0; j--) {
                if (javaLines[j].contains("-> {")) {
                    return null;
                }
                Matcher method = VOID_METHOD.matcher(javaLines[j]);
                if (method.find()) {
                    return method.group(1) != null ? method.group(1) : method.group(2);
                }
            }
        }
        return null;
    }

    /** The data class of the file that has a field of this name, or null. */
    private static String dataClassWith(String[] javaLines, String field) {
        Pattern component = Pattern.compile("[(,][^,()]* " + Pattern.quote(field) + "[,)]");
        for (String line : javaLines) {
            Matcher record = RECORD.matcher(line);
            if (record.find() && component.matcher(record.group(2)).find()) {
                return record.group(1);
            }
        }
        return null;
    }

    private static String firstConstant(String[] javaLines, String enumName) {
        Pattern declaration = Pattern.compile("\\benum " + Pattern.quote(enumName) + " \\{\\s*(\\w+)");
        for (String line : javaLines) {
            Matcher matcher = declaration.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "NAME";
    }

    /** Java gives a mix of types, as from `if (c) 1 else "a"`, as an intersection such as `Object&Serializable&...`. */
    private static String mismatch(String expected, String found) {
        if (isIntersection(found)) {
            return "type mismatch: expected `" + axlType(expected) + "`, found values of different types; give every "
                    + "branch of the `if` or `?:`, or every element, the same type";
        }
        return "type mismatch: expected `" + axlType(expected) + "`, found `" + axlType(found) + "`";
    }

    private static boolean isIntersection(String type) {
        int depth = 0;
        for (char c : type.toCharArray()) {
            depth += c == '<' ? 1 : c == '>' ? -1 : 0;
            if (c == '&' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /** javac lists private overloads of JDK classes too, which nobody outside can call. */
    private static boolean isPrivate(String owner, String name, String parameters) {
        if (!owner.startsWith("java.")) {
            return false;
        }
        Class<?> type;
        try {
            type = Class.forName(owner, false, Messages.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        List<String> wanted = splitTopLevel(parameters).stream()
                .map(p -> p.replaceAll("<.*>", "").replace("...", "[]")).toList();
        Stream<? extends Executable> candidates = name.equals(type.getSimpleName())
                ? Arrays.stream(type.getDeclaredConstructors())
                : Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name));
        return candidates
                .filter(e -> Arrays.stream(e.getParameterTypes()).map(p -> p.getTypeName().replace('$', '.')).toList().equals(wanted))
                .anyMatch(e -> Modifier.isPrivate(e.getModifiers()));
    }

    /** Axolotl has no casts, so name a method that converts. */
    private static String lossyConversion(String from, String to) {
        String conversion = switch (from + " " + to) {
            case "long int" -> "`Math.toIntExact(x)`";
            case "double long" -> "`Math.round(x)`";
            case "double int" -> "`Math.toIntExact(Math.round(x))`";
            default -> null;
        };
        return "`" + axlType(from) + "` does not always fit in `" + axlType(to) + "`; convert it explicitly"
                + (conversion == null ? "" : ", for example with " + conversion);
    }

    /**
     * `try e` and `mapResult` on something that is not a `Result`: javac complains about the code they expand to.
     * Only errors in that code count; the rest of a line with `try` is the author's own code.
     */
    private static Translated tryProblem(String message, String text, String[] javaLines, int javaLine) {
        boolean check = TRY_CHECK.matcher(text.strip()).matches();
        String construct = check && javaLine < javaLines.length && javaLines[javaLine].contains(".add(Result.<")
                ? "mapResult" : "try";
        if (check) {
            Translated errorType = tryErrorType(message, construct, javaLines, javaLine);
            if (errorType != null) {
                return errorType;
            }
        }
        Matcher element = MAP_RESULT_ELEMENT.matcher(message);
        if (text.contains(".add(Result.<") && element.find()) {
            return new Translated(ErrorCode.JAVAC, "`mapResult` gives `" + axlType(element.group(1))
                    + "` values here, but the list holds `" + axlType(element.group(2)) + "`");
        }
        String found = check ? checkedType(message) : null;
        Matcher wrongArgument = WRONG_TRY_ARGUMENT.matcher(javacMessage(message, "", javaLines));
        if (found == null && wrongArgument.find()) {
            found = wrongArgument.group(1);
        }
        if (found == null) {
            return null;
        }
        String problem = construct.equals("try") ? "`try` needs a `Result`" : "the lambda of `mapResult` must return a `Result`";
        return new Translated(ErrorCode.TRY_NOT_RESULT, problem + ", found `" + axlType(found) + "`");
    }

    /** The type of the value whose `isErr()` the check of `try` calls, when it is not a `Result`. */
    private static String checkedType(String message) {
        Matcher notDereferenced = NOT_DEREFERENCED.matcher(message);
        if (notDereferenced.find()) {
            return notDereferenced.group(1);
        }
        Matcher noIsErr = NO_IS_ERR.matcher(message);
        return noIsErr.find() ? noIsErr.group(1) : null;
    }

    /** `E` of `Result.propagate` is invariant, so a different error type fails inference there. */
    private static Translated tryErrorType(String message, String construct, String[] javaLines, int javaLine) {
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
        return new Translated(ErrorCode.TRY_ERROR_TYPE, "`" + construct + "`: the error is `" + actual
                + "`, but the function returns errors of type `" + expected + "`; convert it with `mapErr`");
    }

    /** `java.util.Map<String, Int>` as `Map<String, Int>`, the way the function's own type is written. */
    private static String simpleNames(String type) {
        return type.replaceAll("\\b[a-z]\\w*(?:\\.[a-z]\\w*)*\\.(?=[A-Z])", "");
    }

    /** Messages must not show what the translator generated; say what it stands for instead. */
    private static String hideGenerated(String message) {
        // `shape$1` holds `shape` where a type test narrowed it, and `e$e1` the subject `when (val e = ...)` binds.
        String result = message.replaceAll("\\b([A-Za-z]\\w*)\\$e?\\d+\\b", "$1")
                .replaceAll("`\\$n\\d+` of type `", "the value of type `");
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

    /** javac shows a lambda argument in Java syntax: `map((s)->s.length())`. */
    private static String withoutLambdas(String symbol) {
        int open = symbol.indexOf('(');
        if (open < 0 || !symbol.contains("->")) {
            return symbol;
        }
        String args = symbol.substring(open + 1, symbol.length() - 1);
        List<String> shown = splitTopLevel(args).stream().map(a -> a.contains("->") ? "{ ... }" : a.strip()).toList();
        return symbol.substring(0, open) + "(" + String.join(", ", shown) + ")";
    }

    private static String argumentList(String javac) {
        if (javac.equals("no arguments")) {
            return "";
        }
        return String.join(", ", splitTopLevel(javac).stream().map(Messages::axlType).toList());
    }

    private static String axlType(String javaType) {
        if (isIntersection(javaType)) {
            return "Object";
        }
        String type = NULLABLE_TYPE.matcher(javaType.strip()).replaceAll("$1?");
        type = simpleNames(QUALIFIED_TYPE.matcher(type).replaceAll("$1"));
        return PRIMITIVE.matcher(type).replaceAll(m -> switch (m.group(1)) {
            case "int", "Integer" -> "Int";
            case "char" -> "Character";
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
