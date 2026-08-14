package org.axl.javac;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final Pattern UNUSED_LOCAL = Pattern.compile(
            "^\\[UnusedVariable] The (?:local variable|parameter) '(\\w+)' is never read");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern UNUSED_GENERATED = Pattern.compile("'(?:it|\\$\\w+|\\w+\\$[es]?\\d+)'");
    private static final Pattern IGNORED_RESULT = Pattern.compile("^\\[ReturnValueIgnored] Return value of '(\\w+)' must be used");
    private static final Pattern ERROR_PRONE_LINK = Pattern.compile("\\.?;? ?\\(see https://errorprone\\.info/\\S+\\)");
    private static final Pattern NOT_DEREFERENCED = Pattern.compile("^(\\w+) cannot be dereferenced$");
    // The value may be a literal with a fraction: `3.7.toInt()`.
    private static final Pattern KOTLIN_CONVERSION = Pattern.compile("\\b(\\d+\\.\\d+|\\w+)\\.(toDouble|toInt|toLong)\\(\\)");
    private static final Pattern TO_TEXT = Pattern.compile("\\b(\\w+(?:\\([^()]*\\))?)\\.toString\\(\\)");
    private static final Pattern BAD_OPERANDS = Pattern.compile("^bad operand types for binary operator '(.+?)'; first type: (.+?); second type: (.+)$");
    private static final Pattern BIG_OPERANDS = Pattern.compile("^(\\w+)\\s*([-+*/%]|[<>]=?)\\s*(\\w+)");
    private static final Pattern DUPLICATE_METHOD = Pattern.compile("^method (\\w+)\\(.*\\) is already defined in class .+$");
    private static final Pattern NAME_CLASH = Pattern.compile("^name clash: (\\w+)\\((.*?)\\) and (\\w+)\\((.*)\\) have the same erasure$");
    private static final Pattern VAR_INITIALIZER = Pattern.compile("^cannot infer type for local variable (\\w+); \\(variable initializer is '(void|null)'\\)$");
    private static final Pattern WRONG_TRY_ARGUMENT = Pattern.compile("^wrong arguments for `(?:propagate|unwrap)`: expected \\(.*\\), got \\((.+)\\)$");
    private static final Pattern LAMBDA_INITIALIZER = Pattern.compile("^cannot infer type for local variable (\\w+); \\((lambda expression|method reference) needs an explicit target-type\\)$");
    private static final Pattern UNREPORTED = Pattern.compile("^unreported exception ([\\w.$]+); must be caught or declared to be thrown$");
    // Only the `close()` of the resource of a `use` throws; javac names the resource variable after the plain message.
    private static final Pattern UNREPORTED_CLOSE = Pattern.compile("^unreported exception ([\\w.$]+); must be caught or declared "
            + "to be thrown; exception thrown from implicit call to close\\(\\) on resource variable '(\\w+)'$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass (\\w+)");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][\\w$]*$");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("\\b(?:java\\.lang|axl\\.lang)\\.([A-Z]\\w*)");
    private static final Pattern NULLABLE_TYPE = Pattern.compile("@(?:org\\.jspecify\\.annotations\\.)?Nullable ([\\w.]+(?:<[^<>]*(?:<[^<>]*>[^<>]*)*>)?)");
    // Axolotl names only these; `float`, `short` and `byte` keep Java's names, since `Float` would mean the boxed class.
    private static final Pattern PRIMITIVE = Pattern.compile("\\b(int|long|boolean|double|char|Integer)\\b");
    private static final Pattern GENERATED = Pattern.compile(
            "\\$(?:t|n|c|p|ex)\\d+|\\bNulls\\.|\\bResult\\.(?:unwrap|propagate|attempt)\\b|^(?:unwrap|propagate)\\(\\)$|requireNonNull");
    private static final Pattern SUGGESTION = Pattern.compile(";? ?(?:Did you mean |\\(Note: ).*$");
    // `is` becomes a type pattern in a switch or after instanceof.
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\bcase [\\w.<>]+ \\w+(?:\\$\\d+)? ->|\\binstanceof\\b");
    // A chain of names and calls that ends in a call, such as `first$1.value()` or `m.get("k")`.
    private static final String CALL_CHAIN = "(?:[\\w$]+(?:\\([^()]*\\))?\\.)*[\\w$]+\\([^()]*\\)";
    // `first$1` of a type test or record pattern, which stands for what the author tested.
    private static final Pattern GENERATED_BINDING = Pattern.compile("\\w+\\$\\d+");
    private static final Pattern PRIMITIVE_ARGUMENT = Pattern.compile("[<,]\\s*(int|long|double|boolean|char)\\s*[,>]");
    private static final Pattern SELF_REFERENCE = Pattern.compile(
            "^cannot infer type for local variable (\\w+); \\(cannot use 'var' on self-referencing variable\\)$");
    private static final Pattern UNINITIALIZED = Pattern.compile("^variable (\\w+) might not have been initialized$");
    private static final Pattern NON_STATIC = Pattern.compile("^non-static (method|variable) (\\w+)(\\(.*\\))? cannot be referenced from a static context$");
    // One line with several uses of the parameter gets the message once.
    private static final String STATIC_TYPE_PARAMETER = "is a type parameter of the type of this file";
    private static final Pattern FINAL_PARENT = Pattern.compile("^cannot inherit from final ([\\w.$]+)$");
    private static final Pattern STATIC_TYPE_VARIABLE = Pattern.compile("^non-static type variable (\\w+) cannot be referenced from a static context$");
    private static final Pattern FOR_EACH = Pattern.compile("^for-each not applicable to expression type; required: array or java\\.lang\\.Iterable; found: (.+)$");
    private static final Pattern UNARY = Pattern.compile("^bad operand type (.+) for unary operator '(.+)'$");
    // javac qualifies the class of a file, `app.Worker`, and names a nested one plainly.
    private static final Pattern MISSING_METHOD = Pattern.compile(
            "^([\\w.$]+) is not abstract and does not override abstract method (\\w+)\\((.*)\\) in ([\\w.$]+)$");
    private static final Pattern RESULT_CLASH = Pattern.compile("^(\\w+)\\((.*)\\) in (\\w+) cannot (?:implement|override) "
            + "\\1\\(.*\\) in ([\\w.$]+); return type (\\S+) is not compatible with (\\S+)$");
    // The declaration of a method of a class, as generated: `public int next() {`.
    private static final Pattern CLASS_METHOD = Pattern.compile("^\\s+public (?:static )?(?:final )?([\\w.]+(?:<.*>)?(?:\\[\\])?) (\\w+)\\((.*)\\) \\{$");
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements (.+?) \\{$");
    private static final String GENERIC_INTERFACE = "a Java generic interface takes and gives `Integer`, the class behind `Int`";
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:public |private )?(?:static )?(?!new )[\\w<>?,. @\\[\\]]+ (\\w+)\\([^()]*\\) \\{$");
    private static final Pattern VOID_METHOD = Pattern.compile("\\bstatic void (\\w+)\\(|^\\s*public void (\\w+)\\(");
    private static final Pattern RECEIVER = Pattern.compile("\\b([A-Z]\\w*)\\.(\\w+)\\b");
    private static final Pattern NON_NULL_LOCAL = Pattern.compile(
            ", but `(\\w+)` (?:is not nullable|has the type of its first value, `[^`]+`, which is not nullable);");
    // A lambda where the type is not an interface: `fn f() -> Int = { return 1 }`.
    private static final Pattern NOT_FUNCTIONAL = Pattern.compile("^incompatible types: [\\w.<>, ]+ is not a functional interface$");
    private static final Pattern TYPE_ARITY = Pattern.compile("^wrong number of type arguments; required (\\d+)$");
    private static final Pattern OUT_OF_BOUNDS = Pattern.compile("^type argument (.+?) is not within bounds of type-variable (\\w+)$");
    private static final String WITHIN_BOUND = "; use a type within the bound";
    private static final Pattern IMPORTED_TWICE = Pattern.compile(
            "^a type with the same simple name is already defined by the single-type-import of ([\\w.]+)$");
    private static final Pattern CALL_WITH_NULL = Pattern.compile("(?:new )?([\\w.]+)\\(([^()]*\\bnull\\b[^()]*)\\)");
    private static final Pattern UNBOXED = Pattern.compile("^unboxing of a @Nullable expression '(.+)'");
    private static final Pattern GENERIC_TYPE = Pattern.compile("\\b((?:[a-z]\\w*\\.)*[A-Z]\\w*)<");
    private static final Pattern NEVER_READ = Pattern.compile("^`(\\w+)` is never read$");
    private static final Pattern USED_BEFORE = Pattern.compile("^`(\\w+)` is used before it has a value$");
    private static final Pattern ABSTRACT = Pattern.compile("^([\\w.$]+) is abstract; cannot be instantiated$");
    private static final Pattern ELVIS_FALLBACK = Pattern.compile("\\?:\\s*(\\S)");
    private static final Pattern ATTEMPT_FALLBACK = Pattern.compile("\\belse\\s+(?:\\w+\\s*->\\s*)?(\\S)");
    private static final Pattern ATTEMPT = Pattern.compile("\\battempt\\b");
    private static final String ATTEMPT_STATEMENT = "`attempt` around a call that returns nothing is a statement, not a value; ";
    private static final Pattern IMPORTED_NESTED = Pattern.compile("^import (?:[a-z]\\w*\\.)*([A-Z]\\w*)\\.([A-Z]\\w*);$");
    private static final Pattern IMPORTED_MEMBER = Pattern.compile("^`[\\w.]+` has no function or value `(\\w+)`$");
    private static final Pattern PIPE = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"|[\\w.()]+)\\s*\\|>");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    private static final Pattern TRY = Pattern.compile("\\btry\\b");
    private static final Pattern FIELD_ASSIGNMENT = Pattern.compile("^\\s*(?:[\\w.]+\\.)?(\\w+)\\s*=\\s*(.+);$");
    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(\\w+)\\s*[-+*/%]?=(?!=)");
    // The messages of an import that failed, with the name it would have brought in.
    private static final Pattern FAILED_IMPORT = Pattern.compile("^(?:`[\\w.]+` has no function or value `(\\w+)`"
            + "|unknown type `(?:\\w+\\.)*(\\w+)`; check the spelling$|`(\\w+)` is declared in |`(\\w+)` is private to "
            + "|`[\\w.]+\\.(\\w+)` is not a type: |`(?:\\w+\\.)*(\\w+)` is the type of the file )");
    private static final Pattern UNKNOWN_USE = Pattern.compile("^unknown (?:function|name|type) `(\\w+)");
    private static final String UNKNOWN_TYPE = "unknown type `";
    private static final String UNKNOWN_NAME = "unknown name `";
    // The build adds where a type of another file is declared, to an unknown type and to an unknown name alike.
    static final String DECLARED_IN = "`; it is declared in ";
    // `Level.LOW` with `Level` unknown: javac reads `Level` as a package and points after the dot.
    private static final Pattern UNKNOWN_PACKAGE = Pattern.compile("^unknown package `(\\w+)`");
    private static final Pattern PRIMITIVE_ARRAY = Pattern.compile("^(?:int|long|double|float|short|byte|char|boolean)\\[\\]$");
    private static final Pattern SIMPLE_NAME = Pattern.compile("^[a-z_]\\w*$");
    private static final String PIPE_SUBJECT = "`|>` needs a collection or a stream";
    private static final String SORTED_ARITY = "`sorted` takes a comparator with two parameters: "
            + "`sorted { a, b -> a.compareTo(b) }`";
    private static final Pattern RECORD = Pattern.compile("\\brecord (\\w+)(\\(.*\\))");
    private static final Pattern NEW_TYPE = Pattern.compile("\\bnew ([\\w.]+)(<[^(]*>)?\\(");
    private static final Pattern VALUE_NAME = Pattern.compile("\\bvar (\\w+) = new ");
    private static final Pattern LAMBDA_ARGUMENT = Pattern.compile("^(?:\\(([\\w\\s,]*)\\)|(\\w+)) ->");

    private static final Pattern MUST_GIVE = Pattern.compile("^`(\\w+)` must give `fn ");
    private static final Pattern GENERIC_INTERFACE_CLASS = Pattern.compile("^`fn [^`]*` of `(\\w+)` does not implement `");
    private static final Pattern NO_PACKAGE = Pattern.compile("^package ([\\w.]+) does not exist$");
    private static final String LAMBDA_PARAMETERS = " parameters; name them, as in `{ ";
    private static final Pattern TYPED_LAMBDA = Pattern.compile("\\b([A-Z]\\w*)(?:<.*>)? \\w+ = (\\(\\)|it|\\([^()]*\\)|\\w+) ->");
    private static final String LAMBDA_RETURN_VALUE = "`return` in a lambda ends only the lambda, not ";
    private static final String LAMBDA_VALUE = "the lambda returns a value, but nothing is expected here; "
            + "use the value in a call, such as `println(...)`, or remove it";
    private static final String UNKNOWN_IMPORTED_CLASS = "` in this import; check the spelling";
    private static final Pattern RETURNED_NAME = Pattern.compile("^\\s*return (\\w+);$");
    private static final String GENERATED_VALUE = "the result of `value()` may be null: its type is a type argument written with `?`";
    private static final String NO_RESULT_TYPE = "a function that returns nothing has no result type; remove ";
    static final Pattern RESULT_TYPE = Pattern.compile("->\\s*(?:Void|Unit)\\b");

    private Messages() {}

    /**
     * Where a message belongs in its .axl line. operator: at the operator javac points at, for messages about
     * an operator rather than the whole expression. anchor: the part of the line to use when javac points into
     * code generated for it, such as the lambda of `?:`; its group 1, if present, marks the exact place.
     * anchorFirst: the anchor is better than any place javac points at, which is code generated for it.
     */
    record Placement(boolean operator, Pattern anchor, boolean anchorFirst) {}

    static Placement placement(String translated, String javaText) {
        boolean operator = translated.contains("` cannot be applied to `") || translated.equals(ErrorProneMessages.BOXED_EQUALITY)
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
        Matcher mustGive = MUST_GIVE.matcher(translated);
        if (mustGive.find()) {
            return Pattern.compile("\\bclass\\s+(" + Pattern.quote(mustGive.group(1)) + ")\\b");
        }
        Matcher genericInterface = GENERIC_INTERFACE_CLASS.matcher(translated);
        if (genericInterface.find()) {
            return Pattern.compile("\\bclass\\s+(" + Pattern.quote(genericInterface.group(1)) + ")\\b");
        }
        if (translated.startsWith(NO_RESULT_TYPE)) {
            return RESULT_TYPE;
        }
        if (translated.contains("` has no result type, so it cannot return a value")
                || translated.startsWith(LAMBDA_RETURN_VALUE)) {
            return RETURN;
        }
        if (translated.startsWith(ATTEMPT_STATEMENT)) {
            return ATTEMPT;
        }
        Matcher unknownPackage = UNKNOWN_PACKAGE.matcher(translated);
        if (unknownPackage.find()) {
            return Pattern.compile("\\b(" + Pattern.quote(unknownPackage.group(1)) + ")\\.");
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

    /** The Java line of a translated message: javac reports a missing `return` at the end of the function, not at `-> Void`. */
    static int javaLine(String translated, String[] javaLines, int javaLine) {
        if (!translated.startsWith(NO_RESULT_TYPE)) {
            return javaLine;
        }
        for (int i = Math.min(javaLine, javaLines.length) - 1; i >= 0; i--) {
            if (METHOD.matcher(javaLines[i].strip()).find()) {
                return i + 1;
            }
        }
        return javaLine;
    }

    /**
     * An unknown type, or a name that another file of the build declares, is reported where it is first used
     * in a file, with the number of its other uses; each of them would repeat the same message and the same
     * import to add. sorted is in file order.
     */
    static List<AxlDiagnostic> unknownTypesOnce(List<AxlDiagnostic> sorted) {
        Map<String, Long> uses = new HashMap<>();
        for (AxlDiagnostic d : sorted) {
            if (oncePerFile(d.message())) {
                uses.merge(d.file() + "\n" + d.message(), 1L, Long::sum);
            }
        }
        List<AxlDiagnostic> result = new ArrayList<>();
        for (AxlDiagnostic d : sorted) {
            String key = d.file() + "\n" + d.message();
            Long count = uses.remove(key);
            if (!oncePerFile(d.message()) || count != null && count == 1) {
                result.add(d);
            } else if (count != null) {
                String more = " (and " + (count - 1) + " more use" + (count == 2 ? "" : "s") + " in this file)";
                result.add(new AxlDiagnostic(d.file(), d.line(), d.column(), d.severity(), d.code(),
                        d.message().replaceFirst("^(unknown (?:type|name) `[^`]*`)", "$1" + Matcher.quoteReplacement(more))));
            }
        }
        return result;
    }

    private static boolean oncePerFile(String message) {
        return message.startsWith(UNKNOWN_TYPE) || message.startsWith(UNKNOWN_NAME) && message.contains(DECLARED_IN);
    }

    /** Whether the error only follows from another error of the same file, which already explains the mistake. */
    static boolean followsFrom(AxlDiagnostic effect, List<AxlDiagnostic> all) {
        return all.stream().anyMatch(cause -> cause != effect && cause.file().equals(effect.file())
                && (cause.isError() && causes(cause, effect) || cause.line() == effect.line() && repeats(cause, effect)));
    }

    /** Warnings of two Error Prone checks about the same mistake, of which the second says less. */
    private static boolean repeats(AxlDiagnostic first, AxlDiagnostic second) {
        Matcher neverRead = NEVER_READ.matcher(first.message());
        if (neverRead.find()) {
            return second.message().equals("`" + neverRead.group(1) + "`" + ErrorProneMessages.FILLED_NOT_READ);
        }
        return ErrorProneMessages.isObsoleteClass(first.message()) && second.message().startsWith(ErrorProneMessages.CLASS_TYPE);
    }

    private static boolean causes(AxlDiagnostic cause, AxlDiagnostic effect) {
        boolean sameLine = cause.line() == effect.line() && cause.column() <= effect.column();
        if (sameLine && (cause.message().startsWith(PIPE_SUBJECT) || cause.message().equals(SORTED_ARITY))) {
            return true;
        }
        // javac reports a method that gives `int` for a generic interface's `Integer` at the class and at the method.
        if (cause.message().contains(GENERIC_INTERFACE) && cause.message().equals(effect.message())) {
            return cause.line() > effect.line();
        }
        // The names the lambda uses are unknown because its parameters are not declared.
        if (cause.line() == effect.line() && cause.message().contains(LAMBDA_PARAMETERS)) {
            return effect.message().startsWith("unknown name `");
        }
        if (effect.message().startsWith(NO_RESULT_TYPE + "`-> Unit`")) {
            return cause.message().startsWith("unknown type `Unit`");
        }
        Matcher nonNullLocal = NON_NULL_LOCAL.matcher(cause.message());
        if (nonNullLocal.find() && cause.line() < effect.line()) {
            String name = nonNullLocal.group(1);
            // NullAway follows the value that was stored, not the declared type of the local.
            return effect.message().startsWith("value `" + name + "` may be null")
                    || effect.message().startsWith("`" + name + "` may be null, but the parameter")
                    || effect.message().startsWith("`" + name + "` may be null, but the result type");
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
                .replaceAll("@\\S+ ", "").replaceAll("<.*", "");
        if (type.equals("axl.lang.Result")) {
            return "; a `Result` holds a value only when it is `Ok`: check it with `is Ok` and read it with parentheses, `"
                    + name + "()`, or use `try`";
        }
        try {
            boolean method = Arrays.stream(Class.forName(type, false, Messages.class.getClassLoader()).getMethods())
                    .anyMatch(m -> m.getName().equals(name) && m.getParameterCount() == 0);
            return method ? "; `" + name + "` is a method: `" + name + "()`" : "";
        } catch (ClassNotFoundException | LinkageError e) {
            return "";
        }
    }

    /** `n.toString()`, `n.toDouble()` and `c.equals("a")` on a primitive value, which Kotlin allows. */
    private static String primitiveHint(String javaType, String text) {
        Matcher toText = TO_TEXT.matcher(text);
        if (toText.find()) {
            return "; for its text, write `String.valueOf(" + toText.group(1) + ")`";
        }
        Matcher conversion = KOTLIN_CONVERSION.matcher(text);
        if (conversion.find()) {
            String value = conversion.group(1);
            return switch (conversion.group(2) + " of " + javaType) {
                case "toDouble of int", "toDouble of long" -> "; for a `Double`, write `1.0 * " + value + "` or `Double.valueOf(" + value + ")`";
                case "toInt of double" -> "; for an `Int`, write `Double.valueOf(" + value + ").intValue()`, which drops the fraction, "
                        + "or `Math.toIntExact(Math.round(" + value + "))`, which rounds";
                case "toInt of long" -> "; for an `Int`, write `Math.toIntExact(" + value + ")`";
                case "toLong of double" -> "; for a `Long`, write `Double.valueOf(" + value + ").longValue()`, which drops the fraction, "
                        + "or `Math.round(" + value + ")`, which rounds";
                case "toLong of int" -> "; an `Int` fits a `Long` as it is: `val l: Long = " + value + "`";
                default -> "";
            };
        }
        return javaType.equals("char") && text.contains(".equals(") ? "; compare it with `==`, as in `c == 'a'`" : "";
    }

    /**
     * `s.lenght()` for `s.length()`: a Kotlin name, or a method of the class one or two letters away. A
     * class of the build is looked for in the generated file.
     */
    private static String similarMethod(String location, String name, String[] javaLines) {
        String kotlin = Names.methodHint(name);
        if (kotlin != null) {
            return "; " + kotlin;
        }
        int ofType = location.indexOf(" of type ");
        String type = (ofType > 0 ? location.substring(ofType + " of type ".length()) : location.substring(location.indexOf(' ') + 1))
                .replaceAll("@\\S+ ", "").replaceAll("<.*", "");
        List<String> methods;
        try {
            methods = Arrays.stream(Class.forName(type, false, Messages.class.getClassLoader()).getMethods())
                    .map(Method::getName).distinct().toList();
        } catch (ClassNotFoundException | LinkageError e) {
            String similar = Names.similar(name, javaLines);
            return similar == null ? "" : "; did you mean `" + similar + "`?";
        }
        String similar = Names.closest(name, methods);
        return similar == null ? "" : "; did you mean `" + similar + "`?";
    }

    /** `Pointt` for `Point`: a type of the file, then a type Axolotl or the JDK has. */
    private static String unknownType(String symbol, String text, String[] javaLines) {
        if (symbol.equals("Array") && text.contains("static void main(")) {
            return "unknown type `Array`; `fn main()` takes no parameters and already has `args`, a Java array of `String`: "
                    + "read it as a list with `List.of(args)`";
        }
        List<String> types = nestedTypes(javaLines);
        String similar = types.stream().filter(t -> t.equalsIgnoreCase(symbol)).findFirst()
                .orElse(Names.closest(symbol, types));
        return similar == null ? Names.unknownType(symbol) : "unknown type `" + symbol + "`; did you mean `" + similar + "`?";
    }

    /** `nmae` for `name`: a name of the generated file one or two letters away. */
    private static String unknownName(String symbol, String text, String[] javaLines) {
        // `ArrayList<String()` reads as the comparison `ArrayList < String()`.
        Matcher unclosed = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\s*<\\s*(?:new )?([\\w.]+)\\s*\\(").matcher(text);
        if (unclosed.find()) {
            return "`" + symbol + "` is a type; to create one, close its type arguments: `" + symbol + "<"
                    + unclosed.group(1) + ">()`";
        }
        Pattern imported = Pattern.compile("^import [\\w.]+\\." + Pattern.quote(symbol) + ";$");
        String hint = Names.typeHint(symbol) != null && Arrays.stream(javaLines).noneMatch(l -> imported.matcher(l.strip()).find())
                ? Names.typeHint(symbol) : Names.nameHint(symbol);
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
                || translated.endsWith(ErrorProneMessages.NEVER_NULL)
                || translated.endsWith(Names.NO_TYPE_PARAMETERS) || translated.contains(STATIC_TYPE_PARAMETER);
    }

    /**
     * Also warnings about what Axolotl gives no way to avoid, judged by the Java line javaText, and
     * warnings that follow from another one about the .axl file axlSource.
     */
    static boolean ignored(String message, String javaText, String axlSource) {
        Matcher unused = UNUSED_LOCAL.matcher(message);
        if (!unused.find()) {
            return ignored(message);
        }
        String name = Pattern.quote(unused.group(1));
        // A loop that only counts has no use for its variable.
        boolean loopVariable = Pattern.compile("^\\s*for \\([^:]*\\b" + name + " :").matcher(javaText).find();
        // The warning about the Kotlin template already explains why the name is not read.
        Pattern template = Pattern.compile("\\$(?:" + name + "\\b|\\{[^}]*\\b" + name + "\\b)");
        boolean inTemplate = STRING_LITERAL.matcher(axlSource).results().anyMatch(m -> template.matcher(m.group()).find());
        return loopVariable || inTemplate || ignored(message);
    }

    /**
     * Also an unreported exception inside the call of `attempt`: javac gives up on the call because it
     * returns nothing, which the error about the `attempt` itself already explains. javaColumn is where
     * javac points in javaText, if known.
     */
    static boolean ignored(String message, String javaText, String axlSource, long javaColumn) {
        Attempt attempt = Attempt.in(javaText);
        if (attempt != null && UNREPORTED.matcher(message).find() && attempt.inValue(javaColumn)) {
            return true;
        }
        return ignored(message, javaText, axlSource);
    }

    /**
     * Error Prone's finding that a call always fails is only a warning, so that `attempt`, which handles
     * the failure, compiles; anywhere else it is the error it would have been.
     */
    static boolean alwaysFailsUnhandled(String message, String javaText) {
        return message.startsWith("[" + ErrorProneMessages.ALWAYS_THROWS + "]") && Attempt.in(javaText) == null;
    }

    /**
     * Whether javac points at a type test: `is` becomes `x instanceof T x$1`, reported at `x`, or a
     * `case T x$1 ->` label, reported at `T`. Without a position, any type test on the line counts.
     */
    private static boolean pointsAtTypeTest(String javaText, long javaColumn) {
        if (javaColumn <= 0 || javaColumn > javaText.length()) {
            return TYPE_PATTERN.matcher(javaText).find();
        }
        String rest = javaText.substring((int) javaColumn - 1);
        return rest.matches("^(?:[\\w.$]+|\\([^()]*\\)) instanceof\\b.*") || rest.matches("^(?:case )?[\\w.<>]+ \\w+(?:\\$\\d+)? ->.*");
    }

    /**
     * NullAway reports a lambda of `attempt` that may return null as a method returning null. The
     * value or the error side is named, with its type argument when the `Result` type is written.
     */
    private static String attemptSideNullable(Attempt attempt, boolean error, String resultType) {
        List<String> arguments = resultType == null ? List.of() : splitTopLevel(typeArgumentList(resultType));
        String type = arguments.size() == 2 && !arguments.get(error ? 1 : 0).contains("@Nullable") ? axlType(arguments.get(error ? 1 : 0)) : null;
        String expression = (error ? attempt.error() : attempt.value()).replaceFirst("^new ", "");
        if (error && expression.equals("null")) {
            String written = type == null ? "write the error type with `?`"
                    : "write the error type with `?`: `Result<" + axlType(arguments.get(0)) + ", " + type + "?>`";
            return "the `else` of `attempt` gives the error of the `Result` and cannot be `null`; give it the exception, "
                    + "`else e -> e`, or " + written;
        }
        String side = error ? "the error of `attempt` may be null: `" + expression + "`"
                : "the value of `attempt` may be null: `" + expression + "`";
        if (type == null) {
            return side + " may be null, but the " + (error ? "error" : "value") + " type has no `?`; give a fallback with `?:`";
        }
        String fallback = type.equals("String") && error ? "give a fallback with `?:`, as in `" + expression + " ?: \"unknown error\"`"
                : "give a fallback with `?:`, or write `" + type + "?` as the " + (error ? "error" : "value") + " type";
        return side + " gives a `" + type + "?`, but the " + (error ? "error" : "value") + " type is `" + type + "`; " + fallback;
    }

    /** The `Result` type a line's `attempt` must fit: of the local it is stored in, or of the function it is returned from. */
    private static String resultTypeOf(String text, String[] javaLines, int javaLine) {
        Matcher declared = Pattern.compile("^\\s*(?:final )?(Result<.+>) \\w+ = ").matcher(text);
        if (declared.find()) {
            return declared.group(1);
        }
        String returned = text.strip().startsWith("return ") ? enclosingReturnType(javaLines, javaLine) : null;
        return returned != null && returned.startsWith("Result<") ? returned : null;
    }

    /**
     * `Result.attempt(() -> value, e -> error)` or `Result.orThrow(...)` in a Java line, with the text of
     * both lambda bodies and where the value, the comma, the error and the closing parenthesis are (0-based).
     */
    record Attempt(String value, String error, int valueStart, int comma, int errorStart, int end) {

        private static final Pattern CALL = Pattern.compile("\\bResult\\.(?:<[^>]*>)?(?:attempt|orThrow)\\(\\(\\) -> ");
        private static final Pattern ERROR_LAMBDA = Pattern.compile("^\\s*\\(?(\\w+)\\)? -> ");

        static Attempt in(String javaText) {
            Matcher call = CALL.matcher(javaText);
            if (!call.find()) {
                return null;
            }
            int valueStart = call.end();
            int comma = -1;
            int depth = 0;
            int i = valueStart;
            for (; i < javaText.length(); i++) {
                char c = javaText.charAt(i);
                Matcher literal = c == '"' ? STRING_LITERAL.matcher(javaText).region(i, javaText.length()) : null;
                if (literal != null && literal.lookingAt()) {
                    i = literal.end() - 1;
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (c == ',' && depth == 0 && comma < 0) {
                    comma = i;
                }
            }
            if (comma < 0 || i >= javaText.length()) {
                return null;
            }
            Matcher error = ERROR_LAMBDA.matcher(javaText.substring(comma + 1, i));
            int errorStart = error.find() ? comma + 1 + error.end() : comma + 1;
            return new Attempt(javaText.substring(valueStart, comma).strip(), javaText.substring(errorStart, i).strip(),
                    valueStart, comma, errorStart, i);
        }

        /** Whether a 1-based column of the line is inside the call `attempt` tries. */
        boolean inValue(long javaColumn) {
            return javaColumn > valueStart && javaColumn <= comma;
        }

        /** Whether a 1-based column of the line is inside the `else` branch, from its lambda on. */
        boolean inError(long javaColumn) {
            return javaColumn > comma + 1 && javaColumn <= end;
        }
    }

    /** Warnings about the shape of generated code, which the author cannot change. */
    static boolean ignored(String message) {
        // javac reports an unknown type a second time when it is thrown.
        return message.startsWith("unreported exception <any>;")
                || message.startsWith("[UnnecessaryParentheses]") || message.startsWith("[RefactorSwitch]")
                || message.startsWith("[StatementSwitchToExpressionSwitch]")
                // It asks for a `Locale` at every `toUpperCase()`, which ordinary code has no reason to give.
                || message.startsWith("[StringCaseLocaleUsage]")
                // Axolotl has no @Override; the generated methods cannot carry one the author could write.
                || message.startsWith("[MissingOverride]")
                || message.startsWith("[UnusedVariable]") && UNUSED_GENERATED.matcher(message).find();
    }

    /**
     * @param javaLines the generated file
     * @param javaLine  1-based line of the diagnostic
     */
    static Translated translate(String message, String[] javaLines, int javaLine) {
        return translate(message, javaLines, javaLine, -1);
    }

    /** @param javaColumn 1-based column javac points at in the line, or a non-positive value if unknown */
    static Translated translate(String message, String[] javaLines, int javaLine, long javaColumn) {
        return translate(message, javaLines, javaLine, javaColumn, "");
    }

    /** @param axlText the .axl line the diagnostic is about, or an empty string if unknown */
    static Translated translate(String message, String[] javaLines, int javaLine, long javaColumn, String axlText) {
        Translated translated = rewrite(withoutFileClass(message, javaLines), javaLines, javaLine, javaColumn, axlText);
        String text = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        if (translated.message().contains("`Comparable<") && text.contains("Comparator.naturalOrder()")) {
            return new Translated(translated.code(), "`sorted` needs elements that can be compared, and these cannot; "
                    + "say how to order them: `sorted { a, b -> ... }`");
        }
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

    private static Translated rewrite(String message, String[] javaLines, int javaLine, long javaColumn, String axlText) {
        String text = javaLine >= 1 && javaLine <= javaLines.length ? javaLines[javaLine - 1] : "";
        if (message.startsWith(NullChecker.STRICT_PREFIX)) {
            return new Translated(ErrorCode.STRICT_NULL, message.substring(NullChecker.STRICT_PREFIX.length()));
        }
        if (message.startsWith(RawTypes.PREFIX)) {
            return new Translated(ErrorCode.JAVAC, message.substring(RawTypes.PREFIX.length()));
        }
        if (message.startsWith(PrivateAccess.PREFIX)) {
            return new Translated(ErrorCode.JAVAC, message.substring(PrivateAccess.PREFIX.length()));
        }
        if (message.startsWith(InitCycles.PREFIX)) {
            return new Translated(ErrorCode.INIT_ORDER, message.substring(InitCycles.PREFIX.length()));
        }
        if (message.startsWith(NullChecker.PREFIX)) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, generatedValue(message.substring(NullChecker.PREFIX.length()), text));
        }
        if (message.startsWith(NullChecker.RAW_PREFIX)) {
            return new Translated(ErrorCode.JAVAC, message.substring(NullChecker.RAW_PREFIX.length()));
        }
        if (text.contains("Result.propagate(") || text.contains("Result.unwrap(") || text.contains("Result.<")) {
            Translated tryProblem = tryProblem(message, text, javaLines, javaLine);
            if (tryProblem != null) {
                return tryProblem;
            }
        }
        String returnType = enclosingReturnType(javaLines, javaLine);
        if (message.equals("missing return statement") && ("Void".equals(returnType) || "Unit".equals(returnType))) {
            return new Translated(ErrorCode.JAVAC, NO_RESULT_TYPE + "`-> " + returnType + "`");
        }
        if (message.startsWith("[NullAway] ")) {
            return nullAway(message, text, javaLines, javaLine, javaColumn);
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
            return new Translated(ErrorCode.JAVAC, ErrorProneMessages.translate(plain, text));
        }
        Matcher imported = IMPORTED_CLASS.matcher(text.strip());
        if (imported.find() && message.startsWith("cannot find symbol; symbol: class ")) {
            String name = imported.group(1);
            String owner = name.substring(0, Math.max(0, name.lastIndexOf('.')));
            String ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
            if (ownerSimple.isEmpty() || !Character.isUpperCase(ownerSimple.charAt(0))) {
                return new Translated(ErrorCode.JAVAC, "unknown type `" + name + "`; check the spelling");
            }
            // `import jobs.Job.Job`: a file whose only type has the file's name is that type, not a holder of it.
            if (name.endsWith("." + ownerSimple)) {
                return new Translated(ErrorCode.JAVAC, "`" + owner + "` is the type of the file " + ownerSimple
                        + ".axl and needs no import in its package; elsewhere write `import " + owner + "`");
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
        return new Translated(ErrorCode.JAVAC, SUGGESTION.matcher(javacMessage(message, text, javaLines, javaColumn, axlText)).replaceAll(""));
    }

    /**
     * `value()` of the code that `?:` becomes is the author's value, not a call they wrote, and its type
     * argument is the translator's, not a reason to give them.
     */
    private static String generatedValue(String message, String text) {
        if (!message.startsWith(GENERATED_VALUE)) {
            return message;
        }
        String subject = text.contains("Nulls.or(") ? "the fallback of `?:`" : "this value";
        return subject + " may be null" + message.substring(GENERATED_VALUE.length());
    }

    private static Translated nullAway(String message, String text, String[] javaLines, int javaLine, long javaColumn) {
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
            if (text.contains(value + ".stream()")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`" + value + "` may be null, but `|>` needs a "
                        + "collection; check it for null first or give a default with `?:`");
            }
            if (!narrowable(value, javaLines)) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`" + value + "` may be null: copy it to a `val` "
                        + "and check that, or use `?.`, `?:` or `!!`");
            }
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "value `" + value
                    + "` may be null: use `?.`, `?:` or check `if (" + value + " != null)`");
        }
        Matcher nullableArgument = NULLABLE_ARGUMENT.matcher(message);
        if (nullableArgument.find()) {
            String value = nullableArgument.group(1);
            if (value.equals("null")) {
                String parameter = parameterGivenNull(text, javaLines);
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, parameter == null
                        ? "`null` is passed where null is not accepted; make the type nullable with `?`"
                        : "`null` is passed for `" + parameter + "`, which does not accept null; make its type nullable with `?`");
            }
            boolean name = IDENTIFIER.matcher(value).matches() && !GENERATED.matcher(value).find();
            // `a ?: b` and `a?.b()` are passed as the values of `Nulls.or` and `Nulls.map`.
            boolean elvis = value.contains("Nulls.or(");
            String subject = name ? "`" + value + "`" : elvis ? "the value of `?:`" : value.contains("Nulls.map(") ? "the value of `?.`"
                    : "the argument";
            String check = name ? "check `if (" + value + " != null)`" : "a null check";
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, elvis
                    ? subject + " may be null, since its fallback may be null, but the parameter does not accept null: use a "
                    + "fallback that is not null or a null check"
                    : subject + " may be null, but the parameter does not accept null: use `?:` or " + check);
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
            Attempt attempt = Attempt.in(text);
            if (attempt != null && (attempt.inValue(javaColumn) || attempt.inError(javaColumn))) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, attemptSideNullable(attempt, attempt.inError(javaColumn),
                        resultTypeOf(text, javaLines, javaLine)));
            }
            if (text.contains("Nulls.or(")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "the fallback of `?:` may be null, but the value "
                        + "is not nullable; give a fallback that is not null or add `?` to the type");
            }
            if (text.contains("Nulls.nonNull(")) {
                return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`map` may produce null here, but the list it builds "
                        + "cannot hold nulls; filter them out first or give a default with `?:`");
            }
            Matcher returned = RETURNED_NAME.matcher(text);
            String value = returned.find() && !GENERATED.matcher(returned.group(1)).find() ? "`" + returned.group(1) + "`"
                    : "the returned value";
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, value + " may be null, but the result type has no `?`; "
                    + "add `?` to the result type or return a value that is not null");
        }
        if (plain.startsWith("assigning @Nullable expression to @NonNull field")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, nullableToField(text, javaLines));
        }
        if (plain.startsWith("unboxing of a @Nullable value") || plain.startsWith("unboxing of a @Nullable expression")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, unboxing(plain, text));
        }
        if (plain.startsWith("incompatible types: ") && text.contains(">unwrap(")) {
            return new Translated(ErrorCode.NULLABLE_DEREFERENCE, "`mapResult` may produce null values here, but the list "
                    + "it builds cannot hold nulls; add `?` to the element type or return values that are not null");
        }
        return new Translated(plain.startsWith("incompatible types: ") ? ErrorCode.NULLABLE_DEREFERENCE : ErrorCode.JAVAC,
                javacMessage(plain, text, javaLines, javaColumn));
    }

    /** `n + 1` and `if (b)` with a nullable `n` or `b`, which Java unboxes. */
    private static String unboxing(String plain, String text) {
        Matcher unboxed = UNBOXED.matcher(plain);
        if (!unboxed.find() || GENERATED.matcher(unboxed.group(1)).find()) {
            return "a value that may be null is used as a number or boolean; give a default with `?:`";
        }
        String value = unboxed.group(1).replaceAll("^\\((.*)\\)$", "$1");
        Pattern condition = Pattern.compile("(?:\\b(?:if|while) \\(|!|&&|\\|\\|)\\s*" + Pattern.quote(value) + "\\s*(?:\\)|&&|\\|\\||$)");
        boolean flag = condition.matcher(text).find();
        return "`" + value + "` may be null, but it is used as a " + (flag ? "boolean" : "number") + "; give a default with "
                + "`?:`, as in `(" + value + (flag ? " ?: false)`" : " ?: 0)`");
    }

    /** `b.label = a`, with the property's name and declared type. */
    private static String nullableToField(String text, String[] javaLines) {
        Matcher assigned = FIELD_ASSIGNMENT.matcher(text);
        if (!assigned.find()) {
            return "the value may be null, but the property has no `?` in its type; add `?` to its type or give a default "
                    + "with `?:`";
        }
        String name = assigned.group(1);
        String value = IDENTIFIER.matcher(assigned.group(2)).matches() ? "`" + assigned.group(2) + "`" : "the value";
        Pattern declaration = Pattern.compile("^\\s+public (final )?([\\w.<>?, ]+) " + Pattern.quote(name) + "(?: =.*)?;$");
        Matcher found = Arrays.stream(javaLines).map(declaration::matcher).filter(Matcher::find).findFirst().orElse(null);
        String type = found == null ? "Type" : simpleNames(axlType(found.group(2)));
        String keyword = found != null && found.group(1) != null ? "val" : "var";
        String declared = "`" + name + "` is declared as `" + type + "`";
        String fix = "declare it `" + keyword + " " + name + ": " + type + "?`";
        return value.equals("`null`") ? declared + ", which does not allow null; " + fix
                : value + " may be null, but " + declared + "; " + fix + " or use `?:`";
    }

    /** A null check narrows locals, parameters and `val` properties, but not a call or a `var` property. */
    private static boolean narrowable(String value, String[] javaLines) {
        if (value.contains("(")) {
            return false;
        }
        String name = value.substring(value.lastIndexOf('.') + 1);
        Pattern varField = Pattern.compile("^\\s+public (?!final )[\\w.<>?, @]+ " + Pattern.quote(name) + ";$");
        return !value.contains(".") || Arrays.stream(javaLines).noneMatch(l -> varField.matcher(l).find());
    }

    private static String javacMessage(String message, String text, String[] javaLines) {
        return javacMessage(message, text, javaLines, -1);
    }

    private static String javacMessage(String message, String text, String[] javaLines, long javaColumn) {
        return javacMessage(message, text, javaLines, javaColumn, "");
    }

    /**
     * Plain javac errors, in Axolotl's words and type names; javaColumn is where javac points in text, if
     * known, and axlText is the .axl line, if known.
     */
    private static String javacMessage(String message, String text, String[] javaLines, long javaColumn, String axlText) {
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
        Attempt attempt = Attempt.in(text);
        if (voidResult && attempt != null) {
            return ATTEMPT_STATEMENT + "write it on a line of its own: `attempt " + attemptedCall(attempt, axlText) + " else e -> ...`";
        }
        if (text.contains("Nulls.or(") && message.endsWith(" is not a functional interface)")) {
            return "the right side of `?:` returns nothing, so it cannot be the value when the left side is null";
        }
        String lambdaBody = NOT_FUNCTIONAL.matcher(message).find() ? returnsLambdaOnly(text, javaLines) : null;
        if (lambdaBody != null) {
            return "a function body in braces comes without `=`: `fn " + lambdaBody + "(...) -> Type { ... }`; after `=`, "
                    + "braces make a lambda";
        }
        if (message.equals("incompatible types: incompatible parameter types in lambda expression") && text.contains(".sorted(")) {
            return SORTED_ARITY;
        }
        if (message.equals("incompatible types: incompatible parameter types in lambda expression")) {
            return lambdaArity(text, javaLines);
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
        if (message.startsWith("incompatible types: try-with-resources not applicable to variable type")) {
            return "`use` needs a value with a `close()` method, an `AutoCloseable` such as a reader, a stream or a connection";
        }
        Matcher incompatible = INCOMPATIBLE.matcher(message);
        if (incompatible.find() && pointsAtTypeTest(text, javaColumn)) {
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
            if (privateAccess.group(2) == null && isLibraryRecordField(privateAccess.group(3), name)) {
                return "read the field `" + name + "` of `" + owner + "` with parentheses: `" + name + "()`";
            }
            return constructor ? "`" + owner + "` cannot be created here: its constructor is private"
                    : "`" + name + "` is private to `" + owner + "` and can only be used in that file";
        }
        Matcher noSuitable = NO_SUITABLE.matcher(message);
        if (noSuitable.find() && noSuitable.group(1).matches("println|print") && splitTopLevel(noSuitable.group(2)).size() > 1) {
            return "`" + noSuitable.group(1) + "` takes one value; join them with `+`, as in `println(a + \" \" + b)`";
        }
        if (noSuitable.find(0)) {
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
                return pipeSubject(type, axlText);
            }
            int ofType = location.indexOf(" of type ");
            String owner = ofType > 0 ? location.substring(ofType + " of type ".length()) : location.substring(location.indexOf(' ') + 1);
            if (!inThisFile && owner.endsWith("[]")) {
                String member = unknown.group(1).equals("method") ? "method `" + withoutLambdas(symbol) + "`"
                        : "field `" + symbol + "`";
                return arrayMember(axlType(owner.substring(0, owner.length() - 2)), member, location);
            }
            String constants = unknown.group(1).equals("variable") ? enumConstants(javaLines, simpleNames(axlType(owner))) : null;
            if (!inThisFile && constants != null) {
                return "`" + simpleNames(axlType(owner)) + "` has no constant `" + symbol + "`; its constants are " + constants;
            }
            // `r.value` after `is Ok`: the narrowing took the record apart, so the whole `r` is still a Result.
            boolean resultMember = location.contains("axl.lang.Result") && (name.equals("value") || name.equals("error"));
            boolean resultField = unknown.group(1).equals("variable") && resultMember;
            Tested tested = inThisFile || resultField ? null : testedWithoutNarrowing(location, text, javaLines, axlText);
            if (tested != null) {
                return notNarrowed(tested);
            }
            if (unknown.group(1).equals("method") && resultMember && symbol.equals(name + "()")) {
                return resultSide(location, name);
            }
            String property = unknown.group(1).equals("method") && symbol.equals(name + "()") && !inThisFile
                    ? propertyType(javaLines, simpleNames(axlType(owner)).replaceAll("<.*", ""), name) : null;
            if (property != null) {
                return memberOwner(location) + " has no method `" + symbol + "`" + propertyHint(location, name, simpleNames(axlType(owner)));
            }
            return switch (unknown.group(1)) {
                case "variable" -> inThisFile ? unknownName(symbol, text, javaLines)
                        : memberOwner(location) + " has no field `" + symbol + "`" + methodHint(location, symbol);
                case "method" -> inThisFile ? unknownFunction(symbol, name, javaLines)
                        : staticOwner(location, text) + " has no method `" + withoutLambdas(symbol) + "`"
                        + similarMethod(location, name, javaLines);
                default -> unknownType(symbol, text, javaLines);
            };
        }
        Matcher notDereferenced = NOT_DEREFERENCED.matcher(message);
        if (notDereferenced.find() && text.contains(".stream().")) {
            return pipeSubject(notDereferenced.group(1), axlText);
        }
        if (notDereferenced.find(0)) {
            // A `Character` is a class in the docs, but its value has no methods to call.
            String description = notDereferenced.group(1).equals("char")
                    ? "a `Character` such as `'a'` has no members; its functions belong to the class, as in `Character.isDigit(c)`"
                    : "`" + axlType(notDereferenced.group(1)) + "` is a primitive value and has no members";
            return description + primitiveHint(notDereferenced.group(1), text);
        }
        Matcher badOperands = BAD_OPERANDS.matcher(message);
        if (badOperands.find()) {
            String left = axlType(badOperands.group(2));
            String right = axlType(badOperands.group(3));
            return "`" + badOperands.group(1) + "` cannot be applied to `" + left + "` and `" + right + "`"
                    + operatorMethod(badOperands.group(1), simpleNames(left), simpleNames(right), text, javaColumn);
        }
        if (message.equals("'void' type not allowed here")) {
            return "this function returns nothing, so its result cannot be used as a value";
        }
        Matcher lambdaInitializer = LAMBDA_INITIALIZER.matcher(message);
        if (lambdaInitializer.find()) {
            String kind = lambdaInitializer.group(2).equals("lambda expression") ? "lambda" : "method reference";
            return "write the type of `" + lambdaInitializer.group(1) + "`, since a " + kind + " takes its type from "
                    + "there: `val " + lambdaInitializer.group(1) + ": Type = ...`, with a functional interface such as "
                    + "`Supplier<R>` or `Function<T, R>`";
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
        Matcher unreportedClose = UNREPORTED_CLOSE.matcher(message);
        if (unreportedClose.find()) {
            return "`close()` of `" + resourceType(unreportedClose.group(2), text) + "` can throw `"
                    + axlType(unreportedClose.group(1)) + "`; add `else e -> ...` to the `use`";
        }
        Matcher unreported = UNREPORTED.matcher(message);
        if (unreported.find() && text.strip().startsWith("throw ")) {
            return "`" + axlType(unreported.group(1)) + "` is a checked exception and cannot be thrown here; throw a "
                    + "`RuntimeException`, such as `IllegalStateException`, or return an `Err`";
        }
        if (unreported.find(0) && text.strip().startsWith("try (")) {
            return "opening or closing this resource can throw `" + axlType(unreported.group(1)) + "`; handle it after the block: "
                    + "`use (val r = ...) { ... } else e -> ...`";
        }
        if (unreported.find(0)) {
            String throwsIt = "this call can throw `" + axlType(unreported.group(1)) + "`";
            // Inside `attempt` the call is already wrapped; javac only gives up on it because the call returns nothing.
            return Attempt.in(text) != null ? throwsIt + "; `attempt` catches it only around a call that returns a value"
                    : throwsIt + ", which Axolotl functions cannot pass on; wrap it in `attempt ... else ...` to turn it into a `Result`";
        }
        if (message.equals("static methods cannot be annotated with @Override")) {
            return "`@Override` goes on a method of a class that overrides one, such as `toString`; a function of a file overrides nothing";
        }
        if (message.equals("unreachable statement")) {
            return "this statement is never run: the code before it always returns, throws or loops forever; remove it";
        }
        Matcher noPackage = NO_PACKAGE.matcher(message);
        if (noPackage.find()) {
            return noPackage.group(1).startsWith("kotlin") ? "Axolotl uses Java's library, not Kotlin's; import Java's "
                    + "classes, such as `java.util.List`" : "unknown package `" + noPackage.group(1) + "`; check the spelling, or "
                    + "add the library that has it: a dependency of the build, or `-cp` on the command line";
        }
        Matcher importedTwice = IMPORTED_TWICE.matcher(message);
        if (importedTwice.find()) {
            String type = importedTwice.group(1);
            return "`" + type.substring(type.lastIndexOf('.') + 1) + "` is already imported from `"
                    + type.substring(0, type.lastIndexOf('.')) + "`; use the other one by its full name instead";
        }
        Matcher arity = TYPE_ARITY.matcher(message);
        if (arity.find()) {
            return typeArity(Integer.parseInt(arity.group(1)), text);
        }
        Matcher outOfBounds = OUT_OF_BOUNDS.matcher(message);
        if (outOfBounds.find()) {
            return outOfBounds(simpleNames(axlType(outOfBounds.group(1))), outOfBounds.group(2), text, javaLines);
        }
        if (message.equals("illegal forward reference")) {
            return "a top-level value is used before it is declared; move its declaration up";
        }
        String structural = structuralMessage(message, text, javaLines);
        return structural != null ? structural : message;
    }

    /**
     * The call of an `attempt` as the .axl line writes it: the Java text would show `new`. A line with several
     * takes the one that calls the same method; without the line, a placeholder stands for the call.
     */
    private static String attemptedCall(Attempt attempt, String axlText) {
        String method = attempt.value().replaceFirst("\\(.*$", "").replaceFirst("^.*\\.", "");
        String first = null;
        Matcher keyword = ATTEMPT.matcher(axlText);
        while (keyword.find()) {
            String call = callAfterAttempt(axlText, keyword.end());
            if (call != null && call.replaceFirst("\\(.*$", "").endsWith(method)) {
                return call;
            }
            first = first == null ? call : first;
        }
        return first != null ? first : "<the call>";
    }

    /** The text from the index to the `else` of the same `attempt`, outside of parentheses, or null. */
    private static String callAfterAttempt(String axlText, int start) {
        int depth = 0;
        for (int i = start; i < axlText.length(); i++) {
            char c = axlText.charAt(i);
            Matcher literal = c == '"' ? STRING_LITERAL.matcher(axlText).region(i, axlText.length()) : null;
            if (literal != null && literal.lookingAt()) {
                i = literal.end() - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && axlText.startsWith("else", i) && i > start && Character.isWhitespace(axlText.charAt(i - 1))
                    && (i + 4 == axlText.length() || !Character.isJavaIdentifierPart(axlText.charAt(i + 4)))) {
                String call = axlText.substring(start, i).strip();
                return call.isEmpty() ? null : call;
            }
        }
        return null;
    }

    /**
     * The type of the resource `br` of a `use`, as the header `try (var br = new BufferedReader(...))` shows
     * it: the declared type, the created class, or the name itself when the value comes from a call.
     */
    private static String resourceType(String name, String text) {
        Matcher resource = Pattern.compile("[(;]\\s*(?:final )?([\\w.]+(?:<.*?>)?) " + Pattern.quote(name) + " = (new )?([\\w.]+)")
                .matcher(text);
        if (!resource.find()) {
            return name;
        }
        if (!resource.group(1).equals("var")) {
            return simpleNames(axlType(resource.group(1)));
        }
        return resource.group(2) != null ? simpleNames(resource.group(3)) : name;
    }

    /** `f` of `fn f() -> Int = { ... }`, a function whose whole body is a lambda without parameters, or null. */
    private static String returnsLambdaOnly(String text, String[] javaLines) {
        int line = Arrays.asList(javaLines).lastIndexOf(text);
        Matcher function = METHOD_DECLARATION.matcher(line > 0 ? javaLines[line - 1] : "");
        return text.strip().equals("return () -> {") && function.find() ? function.group(1) : null;
    }

    /** `Map<String>`: the first type on the line with another number of type arguments than required. */
    private static String typeArity(int required, String text) {
        Matcher generic = GENERIC_TYPE.matcher(text);
        while (generic.find()) {
            if (splitTopLevel(angleArguments(text, generic.end())).size() != required) {
                String type = simpleNames(generic.group(1));
                return "`" + type + "` takes " + required + " type argument" + (required == 1 ? "" : "s") + ", as in `"
                        + type + "<" + RawTypes.example(required) + ">`";
            }
        }
        return "this type takes " + required + " type argument" + (required == 1 ? "" : "s");
    }

    /**
     * `Sorted<Object>` for `class Sorted<T : Comparable<T>>`: the first generic type of the line with the type
     * argument, and the bound of its type parameter as the class of the file or a library declares it. For a
     * class of another file the message ends in {@link #WITHIN_BOUND}, and the build completes it.
     */
    private static String outOfBounds(String argument, String variable, String text, String[] javaLines) {
        Matcher generic = GENERIC_TYPE.matcher(text);
        while (generic.find()) {
            List<String> arguments = splitTopLevel(angleArguments(text, generic.end()));
            if (arguments.stream().noneMatch(a -> simpleNames(axlType(a)).equals(argument))) {
                continue;
            }
            String owner = simpleNames(generic.group(1));
            String parameters = typeParameters(generic.group(1), javaLines);
            String bound = parameters == null ? null : boundOf(parameters, variable);
            return bound != null ? typeArgumentBound(argument, variable, owner, bound)
                    : "`" + argument + "` does not fit the bound of `" + variable + "` of `" + owner + "`" + WITHIN_BOUND;
        }
        return "`" + argument + "` does not fit the bound of the type parameter `" + variable + "`" + WITHIN_BOUND;
    }

    /** The type parameters of a type of the file or of a library class, as Java declares them, or null. */
    private static String typeParameters(String type, String[] javaLines) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        Pattern declaration = Pattern.compile("\\b(?:class|interface|record|enum) " + Pattern.quote(simple) + "<");
        for (String line : javaLines) {
            Matcher declared = declaration.matcher(line);
            if (declared.find()) {
                return angleArguments(line, declared.end());
            }
        }
        Pattern imported = Pattern.compile("^import ([\\w.]+\\." + Pattern.quote(simple) + ");$");
        List<String> candidates = new ArrayList<>(List.of("java.lang." + simple, "java.util." + simple));
        Arrays.stream(javaLines).map(l -> imported.matcher(l.strip())).filter(Matcher::find).forEach(m -> candidates.addFirst(m.group(1)));
        if (type.contains(".")) {
            candidates.addFirst(type);
        }
        for (String name : candidates) {
            try {
                return Arrays.stream(Class.forName(name, false, Messages.class.getClassLoader()).getTypeParameters())
                        .map(p -> p.getName() + Arrays.stream(p.getBounds()).map(java.lang.reflect.Type::getTypeName)
                                .filter(b -> !b.equals("java.lang.Object")).map(b -> " extends " + b).collect(Collectors.joining(" &")))
                        .collect(Collectors.joining(", "));
            } catch (ClassNotFoundException | LinkageError e) {
                // Try the next package.
            }
        }
        return null;
    }

    /** `Comparable<T>` of `T extends Comparable<T>, U` for `T`, or null when the parameter has no bound. */
    static String boundOf(String parameters, String variable) {
        for (String parameter : splitTopLevel(parameters)) {
            if (parameter.startsWith(variable + " extends ")) {
                return parameter.substring((variable + " extends ").length());
            }
        }
        return null;
    }

    /** The message about a type argument outside the bound of a type parameter, with the bound as Axolotl writes it. */
    static String typeArgumentBound(String argument, String variable, String owner, String bound) {
        String written = simpleNames(axlType(bound));
        String erased = written.replaceFirst("<.*", "").replaceFirst(" &.*", "");
        String advice = switch (erased) {
            case "Comparable" -> "use a type that is `Comparable`, such as `String` or `Int`";
            case "Number" -> "use a type that is a `Number`, such as `Int` or `Double`";
            case "Enum" -> "use an enum";
            default -> "use a type that is `" + erased + "`";
        };
        return "`" + argument + "` does not fit `" + variable + " : " + written + "` of `" + owner + "`; " + advice;
    }

    /** The message of {@link #outOfBounds} for a type of another file, completed with the bound found there. */
    static String outOfBoundsOf(String message, Function<String, String> typeParameters) {
        Matcher unknownBound = Pattern.compile("^`(\\S+)` does not fit the bound of `(\\w+)` of `(\\w+)`" + Pattern.quote(WITHIN_BOUND) + "$")
                .matcher(message);
        String parameters = unknownBound.find() ? typeParameters.apply(unknownBound.group(3)) : null;
        String bound = parameters == null ? null : boundOf(parameters, unknownBound.group(2));
        return bound == null ? message : typeArgumentBound(unknownBound.group(1), unknownBound.group(2), unknownBound.group(3), bound);
    }

    /** `A, B<C>` of the `<A, B<C>>` that starts after the index; the rest of the text when it is not closed. */
    static String angleArguments(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i++) {
            depth += text.charAt(i) == '<' ? 1 : text.charAt(i) == '>' ? -1 : 0;
            if (depth == 0) {
                return text.substring(start, i);
            }
        }
        return text.substring(start);
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
            String outer = function == null ? functionAroundLambda(javaLines, text) : null;
            if (outer != null) {
                return LAMBDA_RETURN_VALUE + "`" + outer + "`, and this lambda gives no value; compute the result with a "
                        + "pipe, such as `|> anyMatch { ... }`, or use a `for` loop";
            }
            if (function == null) {
                return "nothing is expected here, so `return` cannot give a value";
            }
            // A `main` with a result type is not the start of the program, so the exit code is not its result.
            return "`" + function + "` has no result type, so it cannot return a value; "
                    + (function.equals("main") ? "to end the program with an exit code, call `System.exit(code)`"
                    : "add one after `->`, as in `fn " + function + "(...) -> Int`");
        }
        Matcher nonStatic = NON_STATIC.matcher(message);
        if (nonStatic.find()) {
            return nonStatic(nonStatic.group(1), nonStatic.group(2), text);
        }
        Matcher staticTypeVariable = STATIC_TYPE_VARIABLE.matcher(message);
        if (staticTypeVariable.find()) {
            String name = staticTypeVariable.group(1);
            return "`" + name + "` is a type parameter of the type of this file, which a function of the file cannot use; "
                    + "give the function its own: `fn name<" + name + ">(...)`";
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
        Matcher resultClash = RESULT_CLASH.matcher(message);
        if (resultClash.find()) {
            String boxed = genericInterfaceMethod(javaLines, resultClash.group(3), resultClash.group(1),
                    splitTopLevel(resultClash.group(2)), resultClash.group(4), true);
            return boxed != null ? boxed : "`fn " + resultClash.group(1) + "()` of `" + resultClash.group(3) + "` returns `"
                    + axlType(resultClash.group(5)) + "`, but `" + simpleNames(axlType(resultClash.group(4))) + "` requires `"
                    + simpleNames(resultClash.group(6)) + "`";
        }
        Matcher missingMethod = MISSING_METHOD.matcher(message);
        if (missingMethod.find()) {
            String type = simpleNames(missingMethod.group(1));
            String method = missingMethod.group(2);
            String boxed = genericInterfaceMethod(javaLines, type, method, splitTopLevel(missingMethod.group(3)),
                    missingMethod.group(4), false);
            if (boxed != null) {
                return boxed;
            }
            String parameters = missingMethod.group(3).isEmpty() ? "" : "...";
            String required = "`" + type + "` must give `fn " + method + "(" + parameters + ")`, which `"
                    + simpleNames(axlType(missingMethod.group(4))) + "` requires";
            String property = parameters.isEmpty() ? propertyType(javaLines, type, method) : null;
            return property == null ? required : required + "; a property gives a value only to an interface of the same "
                    + "package, so add `fn " + method + "() -> " + property + " = " + method + "`";
        }
        Matcher abstractType = ABSTRACT.matcher(message);
        if (abstractType.find()) {
            return abstractType(abstractType.group(1), text, javaLines);
        }
        if (message.equals("interface expected here")) {
            return "this supertype is a class, not an interface; to extend a Java class, call its constructor: `: Thread()`. "
                    + "A class written in Axolotl cannot be extended: compose it, or give both an interface";
        }
        Matcher finalParent = FINAL_PARENT.matcher(message);
        if (finalParent.find()) {
            return "`" + simpleNames(axlType(finalParent.group(1))) + "` is written in Axolotl and cannot be extended; "
                    + "keep one in a property and give both an interface";
        }
        return null;
    }

    /**
     * `fn get() -> Int` in a class that implements `Supplier<Int>`: the generated `int get()` does not
     * implement `Integer get()`, since a type argument always stands for the class. The message names
     * the method as written and as it must be written, or is null when the method is not the case.
     * resultOnly: javac reported the result type, so the parameters agree.
     */
    private static String genericInterfaceMethod(String[] javaLines, String className, String method, List<String> required,
                                                 String interfaceName, boolean resultOnly) {
        String interfaceSimple = interfaceName.substring(interfaceName.lastIndexOf('.') + 1);
        Pattern start = Pattern.compile("^(\\s*)(?:public |private |static |final |sealed |non-sealed )*class " + Pattern.quote(className) + "\\b.*$");
        String end = null;
        String implemented = null;
        Matcher declaration = null;
        for (String line : javaLines) {
            Matcher header = start.matcher(line);
            if (end == null && header.find()) {
                end = header.group(1) + "}";
                Matcher implementsClause = IMPLEMENTS.matcher(line);
                implemented = implementsClause.find() ? splitTopLevel(implementsClause.group(1)).stream()
                        .filter(i -> i.replaceAll("<.*", "").endsWith(interfaceSimple) && i.contains("<")).findFirst().orElse(null) : null;
            } else if (end != null && line.equals(end)) {
                break;
            } else if (end != null) {
                Matcher found = CLASS_METHOD.matcher(line);
                if (found.find() && found.group(2).equals(method) && splitTopLevel(found.group(3)).size() == required.size()) {
                    declaration = found;
                    break;
                }
            }
        }
        if (declaration == null || implemented == null) {
            return null;
        }
        List<String> parameters = splitTopLevel(declaration.group(3));
        List<String> written = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < parameters.size(); i++) {
            String type = parameters.get(i).substring(0, parameters.get(i).lastIndexOf(' '));
            String parameterName = parameters.get(i).substring(parameters.get(i).lastIndexOf(' ') + 1);
            boolean boxed = type.equals("int") && !resultOnly && simpleNames(required.get(i)).equals("Integer");
            changed |= boxed;
            written.add(parameterName + ": " + axlType(type));
            fixed.add(parameterName + ": " + (boxed ? "Integer" : axlType(type)));
        }
        String result = declaration.group(1);
        Boolean genericResult = returnsTypeVariable(interfaceName, method, parameters.size());
        boolean boxResult = result.equals("int") && (resultOnly || Boolean.TRUE.equals(genericResult) || genericResult == null && !changed);
        if (!changed && !boxResult) {
            return null;
        }
        String signature = "fn " + method + "(" + String.join(", ", written) + ")" + (result.equals("void") ? "" : " -> " + axlType(result));
        String fixedSignature = "fn " + method + "(" + String.join(", ", fixed) + ")"
                + (result.equals("void") ? "" : " -> " + (boxResult ? "Integer" : axlType(result)));
        return "`" + signature + "` of `" + className + "` does not implement `" + axlType(implemented) + "`: " + GENERIC_INTERFACE
                + ", so write `" + fixedSignature + "`";
    }

    /** Whether the method of a JDK interface returns one of its type variables; null when the interface is not a JDK one. */
    private static Boolean returnsTypeVariable(String interfaceName, String method, int parameterCount) {
        try {
            return Arrays.stream(Class.forName(interfaceName, false, Messages.class.getClassLoader()).getMethods())
                    .filter(m -> m.getName().equals(method) && m.getParameterCount() == parameterCount)
                    .anyMatch(m -> m.getGenericReturnType() instanceof TypeVariable);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** `List<String>()`: an interface or abstract class cannot be created, a class that implements it can. */
    private static String abstractType(String javaName, String text, String[] javaLines) {
        String name = simpleNames(axlType(javaName));
        String simple = name.substring(name.lastIndexOf('.') + 1);
        // Other types may be created on the line before this one, as in `Job("q", Callable<Int>({ -> 1 }))`.
        Matcher created = NEW_TYPE.matcher(text);
        boolean found = false;
        while (!found && created.find()) {
            found = created.group(1).substring(created.group(1).lastIndexOf('.') + 1).equals(simple);
        }
        String typeArguments = found && created.group(2) != null ? axlType(created.group(2)) : "";
        Matcher lambda = LAMBDA_ARGUMENT.matcher(found ? text.substring(created.end()) : "");
        if (lambda.find() && isInterface(javaName, simple, javaLines)) {
            // `Runnable({ -> ... })`: the lambda is the implementation, which only a value of the type takes.
            Matcher value = VALUE_NAME.matcher(text);
            boolean initializer = value.find() && value.end() == created.start() + "new ".length();
            String parameters = lambda.group(1) != null ? lambda.group(1).strip() : lambda.group(2);
            return "`" + name + "` is an interface and cannot be created; pass the lambda where a `" + name + typeArguments
                    + "` is expected" + (simple.equals("Runnable") ? ", such as `Thread({ -> ... })`" : "") + ", or keep it in a value: `val "
                    + (initializer ? value.group(1) : "v") + ": " + name + typeArguments + " = { " + (parameters.isEmpty() ? "" : parameters + " ")
                    + "-> ... }`";
        }
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

    /**
     * `x`, `b.v` or `b.v()` when an `is` test of it comes before this line in the same function: the translator
     * narrows only a `val` local or parameter, and leaves other tests without a binding. A member missing on
     * a class or interface is looked for after a call tested with `is`; axlText names the call as written.
     */
    private static Tested testedWithoutNarrowing(String location, String text, String[] javaLines, String axlText) {
        String variable = variableName(location);
        if (variable == null && !location.startsWith("class ") && !location.startsWith("interface ")) {
            return null;
        }
        String subject = variable != null ? "(?:\\w+\\.)*" + Pattern.quote(variable) : CALL_CHAIN;
        // An `if` condition, the left side of `&&`, or the subject of a `when`.
        Pattern test = Pattern.compile("(?<![\\w.$])(" + subject + ") instanceof ([\\w.<>]+)(?:\\)| &&)"
                + "|\\bswitch \\((" + subject + ")\\) \\{");
        int line = Arrays.asList(javaLines).lastIndexOf(text);
        for (int j = line; j >= 0 && !METHOD_DECLARATION.matcher(javaLines[j]).find(); j--) {
            Matcher found = test.matcher(javaLines[j]);
            while (found.find()) {
                String tested = found.group(1) != null ? found.group(1) : found.group(3);
                if (!Pattern.compile("(?<![\\w.$])" + Pattern.quote(tested + ".")).matcher(text).find()) {
                    continue;
                }
                boolean inWhen = found.group(3) != null;
                if (GENERATED_BINDING.matcher(tested).matches()) {
                    // The binding of a record pattern holds a component, `first.value()` of `first is Some`.
                    String written = inWhen ? null : testedInLine(axlText, found.group(2));
                    return new Tested(written != null ? written : componentCall(tested, javaLines), inWhen, true);
                }
                if (variable == null) {
                    String written = inWhen ? null : testedInLine(axlText, found.group(2));
                    return new Tested(written != null ? written : hideGenerated(tested).replace("new ", ""), inWhen, true);
                }
                return new Tested(tested, inWhen, false);
            }
        }
        return null;
    }

    /** A value tested with `is`: whether as the subject of a `when`, and whether it is a call, null when unknown. */
    private record Tested(String value, boolean subject, boolean call) {}

    private static String notNarrowed(Tested tested) {
        String value = tested.value();
        if (tested.call() && value == null) {
            return "the value tested with `is` here is a call, so `is` does not narrow it; copy it to a `val` first and test that";
        }
        if (tested.call()) {
            String name = calledName(value);
            String problem = "`" + value + "` is a call, so `is` does not narrow it; ";
            return tested.subject() ? problem + "name the subject: `when (val " + name + " = " + value + ")`"
                    : problem + "copy it to a `val` first: `val " + name + " = " + value + "`, and test `" + name + "`";
        }
        String name = value.substring(value.lastIndexOf('.') + 1);
        if (!value.contains(".")) {
            return "`" + value + "` is a `var`, so `is` does not narrow it; copy it to a `val` first, or declare it with "
                    + "`val` if it does not change";
        }
        String problem = "`" + value + "` is a property, so `is` does not narrow it; ";
        return tested.subject() ? problem + "name the subject: `when (val " + name + " = " + value + ")`"
                : problem + "copy it to a local `val` first: `val " + name + " = " + value + "`";
    }

    /**
     * `first.value()` of `first.value() is Punct` in the .axl line, or null: the call or chain of calls tested
     * for the type, as written.
     */
    private static String testedInLine(String axlText, String type) {
        String simple = type.replaceFirst("<.*", "").replaceFirst("^.*\\.", "");
        Matcher test = Pattern.compile("\\s+is\\s+" + Pattern.quote(simple) + "\\b").matcher(axlText);
        while (test.find()) {
            int start = test.start();
            int depth = 0;
            while (start > 0) {
                char c = axlText.charAt(start - 1);
                if (c == ')') {
                    depth++;
                } else if (c == '(') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0 && !Character.isJavaIdentifierPart(c) && c != '.') {
                    break;
                }
                start--;
            }
            String tested = axlText.substring(start, test.start());
            if (tested.contains("(")) {
                return tested;
            }
        }
        return null;
    }

    /**
     * `first.value()` for the binding `first$1` of the record pattern `first instanceof Some(var first$1)`:
     * the component at the binding's position, called on what was tested. Null when the pattern is not found.
     */
    private static String componentCall(String binding, String[] javaLines) {
        Pattern pattern = Pattern.compile("([\\w$.]+) instanceof ([\\w.]+)(?:<[^()]*>)?\\(([^()]*\\bvar " + Pattern.quote(binding) + "\\b[^()]*)\\)");
        for (String line : javaLines) {
            Matcher found = pattern.matcher(line);
            if (!found.find()) {
                continue;
            }
            List<String> bindings = splitTopLevel(found.group(3));
            int index = bindings.indexOf("var " + binding);
            Matcher record = Arrays.stream(javaLines)
                    .map(l -> Pattern.compile("\\brecord " + Pattern.quote(simpleNames(found.group(2))) + "(?:<[^()]*>)?\\(([^()]*)\\)").matcher(l))
                    .filter(Matcher::find).findFirst().orElse(null);
            List<String> components = record == null ? List.of() : splitTopLevel(record.group(1));
            if (index < 0 || index >= components.size()) {
                return null;
            }
            String component = components.get(index);
            return hideGenerated(found.group(1)) + "." + component.substring(component.lastIndexOf(' ') + 1) + "()";
        }
        return null;
    }

    /** `value` for `first.value()`, the name a copy of the call's result would take; `t` for a call with arguments. */
    private static String calledName(String call) {
        String method = call.replaceFirst("\\(.*$", "");
        method = method.substring(method.lastIndexOf('.') + 1);
        return call.endsWith("()") && IDENTIFIER.matcher(method).matches() ? method : "t";
    }

    /** `r` of `variable r of type ...`, the way javac names the location of a member; null for a class or an expression. */
    private static String variableName(String location) {
        int ofType = location.indexOf(" of type ");
        return location.startsWith("variable ") && ofType > 0 ? location.substring("variable ".length(), ofType) : null;
    }

    /** `r.value()` on a `Result` that no `is Ok` test has narrowed: javac sees only the sealed interface. */
    private static String resultSide(String location, String side) {
        String receiver = variableName(location);
        boolean value = side.equals("value");
        String problem = memberOwner(location) + " may be an `" + (value ? "Err" : "Ok") + "`, so it has no `" + side + "()` yet; ";
        if (receiver == null) {
            return problem + "test it with `is " + (value ? "Ok" : "Err") + "` first" + (value ? ", or write `try` before it" : "");
        }
        return problem + "test it first: `if (" + receiver + " is " + (value ? "Ok" : "Err") + ") { ... " + receiver + "." + side
                + "() }`" + (value ? ", or write `try " + receiver + "`" : "");
    }

    /** `c.by()` on `class Inc(val by: Int)`: a property of a class is read without parentheses. */
    private static String propertyHint(String location, String property, String owner) {
        String receiver = variableName(location);
        return "; `" + property + "` is a property of the class `" + owner + "`: read it without parentheses, "
                + (receiver == null ? "`" + property + "`" : "`" + receiver + "." + property + "`");
    }

    /** `s.split(",").size()`: Axolotl has no arrays of its own, but Java methods return them. */
    private static String arrayMember(String element, String member, String location) {
        boolean primitive = Character.isLowerCase(element.charAt(0));
        int ofType = location.indexOf(" of type ");
        String name = location.startsWith("variable ") && ofType > 0 ? location.substring("variable ".length(), ofType) : null;
        String array = name == null ? "this is a Java array" : "`" + name + "` is a Java array";
        String list = "turn it into a list with `List.of(" + (name == null ? "..." : name) + ")`";
        return array + " of `" + element + "`, which has no " + member + "; "
                + (primitive ? "read its `length`" : list + ", or read its `length`");
    }

    /** `name` of `P(null)`: the parameter that the call `null` is passed to declares. */
    private static String parameterGivenNull(String text, String[] javaLines) {
        Matcher call = CALL_WITH_NULL.matcher(text);
        if (!call.find()) {
            return null;
        }
        String callee = call.group(1).substring(call.group(1).lastIndexOf('.') + 1);
        int index = splitTopLevel(call.group(2)).indexOf("null");
        Pattern declaration = Pattern.compile("\\b(?:record )?" + Pattern.quote(callee) + "\\(([^()]*)\\)(?: implements [^{]*)? \\{");
        for (String line : javaLines) {
            Matcher declared = declaration.matcher(line);
            if (declared.find()) {
                List<String> parameters = splitTopLevel(declared.group(1));
                return index >= 0 && index < parameters.size()
                        ? parameters.get(index).substring(parameters.get(index).lastIndexOf(' ') + 1) : null;
            }
        }
        return null;
    }

    /** `val c: Comparator<String> = { a.compareTo(b) }`: the parameters the interface gives the lambda. */
    private static String lambdaArity(String text, String[] javaLines) {
        Matcher typed = TYPED_LAMBDA.matcher(text);
        int wanted = typed.find() ? abstractMethodParameters(typed.group(1), javaLines) : -1;
        if (wanted < 0) {
            return "the lambda has the wrong number of parameters; for one parameter write `{ x -> ... }` or use `it`";
        }
        String type = "`" + typed.group(1) + "`";
        if (wanted == 0) {
            return type + " takes no parameters, so " + (typed.group(2).equals("it") ? "`it` is not available here"
                    : "the lambda cannot have any") + "; write `{ -> ... }`";
        }
        if (wanted == 1) {
            return type + " takes 1 parameter; name it, as in `{ x -> ... }`, or use `it`";
        }
        String names = String.join(", ", List.of("a", "b", "c", "d", "e").subList(0, Math.min(wanted, 5)));
        return type + " takes " + wanted + LAMBDA_PARAMETERS + names + " -> ... }`";
    }

    /** The parameter count of the single abstract method of a JDK interface, or -1. */
    private static int abstractMethodParameters(String simpleName, String[] javaLines) {
        Pattern imported = Pattern.compile("^import ([\\w.]+\\." + Pattern.quote(simpleName) + ");$");
        List<String> candidates = new ArrayList<>(List.of("java.lang." + simpleName, "java.util.function." + simpleName));
        Arrays.stream(javaLines).map(l -> imported.matcher(l.strip())).filter(Matcher::find).forEach(m -> candidates.addFirst(m.group(1)));
        for (String name : candidates) {
            try {
                List<Method> methods = Arrays.stream(Class.forName(name, false, Messages.class.getClassLoader()).getMethods())
                        .filter(m -> Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)).toList();
                return methods.size() == 1 ? methods.getFirst().getParameterCount() : -1;
            } catch (ClassNotFoundException | LinkageError e) {
                // Try the next package.
            }
        }
        return -1;
    }

    /** `Comparator` declares `equals` again, which a lambda does not implement. */
    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The function that a lambda with this `return` is in, or null when the `return` is not in a lambda. */
    private static String functionAroundLambda(String[] javaLines, String text) {
        // javac may place the error at the lambda itself.
        boolean inLambda = false;
        for (int j = Arrays.asList(javaLines).lastIndexOf(text); j >= 0; j--) {
            inLambda |= javaLines[j].contains("-> {");
            Matcher method = METHOD_DECLARATION.matcher(javaLines[j]);
            if (method.find()) {
                return inLambda ? method.group(1) : null;
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

    /** The Axolotl type of a property of a class of the file, or null when the class has no such property. */
    private static String propertyType(String[] javaLines, String className, String property) {
        Pattern start = Pattern.compile("^(\\s*)(?:public |private |static |final )*class " + Pattern.quote(className) + "\\b");
        Pattern field = Pattern.compile("^\\s+(?:public |private )?(?:final )?([\\w.<>?, @]+) " + Pattern.quote(property) + ";$");
        String end = null;
        for (String line : javaLines) {
            Matcher declaration = start.matcher(line);
            Matcher found = field.matcher(line);
            if (end == null && declaration.find()) {
                end = declaration.group(1) + "}";
            } else if (end != null && line.equals(end)) {
                return null;
            } else if (end != null && found.find()) {
                return simpleNames(axlType(found.group(1)));
            }
        }
        return null;
    }

    /** The constants of an enum of the file, as `RED`, `GREEN`, or null. */
    private static String enumConstants(String[] javaLines, String enumName) {
        Pattern declaration = Pattern.compile("\\benum " + Pattern.quote(enumName) + " \\{\\s*(\\w+(?:\\s*,\\s*\\w+)*)");
        return Arrays.stream(javaLines).map(declaration::matcher).filter(Matcher::find).findFirst()
                .map(m -> Arrays.stream(m.group(1).split("\\s*,\\s*")).map(c -> "`" + c + "`").collect(Collectors.joining(", ")))
                .orElse(null);
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

    /** `a + b` on `BigDecimal` values and `"a" < "b"`: the method Java has in place of the operator. */
    private static String operatorMethod(String operator, String left, String right, String text, long javaColumn) {
        boolean comparison = operator.matches("[<>]=?");
        boolean bigNumber = left.equals(right) && (left.equals("BigDecimal") || left.equals("BigInteger"));
        if (!bigNumber && !(comparison && left.equals("String") && right.equals("String"))) {
            return "";
        }
        // javac points at the operator; the operands are named when they are plain names.
        String a = "a";
        String b = "b";
        Matcher operands = BIG_OPERANDS.matcher(text);
        int start = (int) javaColumn - 1;
        while (start > 0 && start <= text.length() && Character.isLetterOrDigit(text.charAt(start - 1))) {
            start--;
        }
        if (javaColumn > 0 && javaColumn <= text.length() && operands.region(start, text.length()).lookingAt()
                && operands.group(2).equals(operator)) {
            a = operands.group(1);
            b = operands.group(3);
        }
        if (comparison) {
            return "; compare them with `" + a + ".compareTo(" + b + ") " + operator + " 0`";
        }
        String method = switch (operator) {
            case "+" -> "add(" + b + ")";
            case "-" -> "subtract(" + b + ")";
            case "*" -> "multiply(" + b + ")";
            case "/" -> left.equals("BigDecimal") ? "divide(" + b + ", 10, RoundingMode.HALF_UP)" : "divide(" + b + ")";
            case "%" -> "remainder(" + b + ")";
            default -> null;
        };
        return method == null ? "" : "; `" + left + "` has methods in place of operators: `" + a + "." + method + "`";
    }

    /** `r.value` of an `Ok`: a record of the runtime or the JDK, whose fields are read with `()`. */
    private static boolean isLibraryRecordField(String owner, String field) {
        try {
            Class<?> type = Class.forName(owner, false, Messages.class.getClassLoader());
            return type.isRecord() && Arrays.stream(type.getRecordComponents()).anyMatch(c -> c.getName().equals(field));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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
            case "long int" -> ", for example with `Math.toIntExact(x)`";
            case "double long" -> ": `Double.valueOf(x).longValue()` drops the fraction, `Math.round(x)` rounds";
            case "double int" -> ": `Double.valueOf(x).intValue()` drops the fraction, `Math.toIntExact(Math.round(x))` rounds";
            default -> "";
        };
        return "`" + axlType(from) + "` does not always fit in `" + axlType(to) + "`; convert it explicitly" + conversion;
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
    static String simpleNames(String type) {
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

    /** `Integer.parse(s)` is about the class `Integer`, which Axolotl does not call `Int`. */
    private static String staticOwner(String location, String text) {
        return location.equals("class java.lang.Integer") && text.contains("Integer.") ? "`Integer`" : memberOwner(location);
    }

    /** `variable list of type java.util.List<java.lang.Integer>` or `class app.Util`. */
    private static String memberOwner(String location) {
        int ofType = location.indexOf(" of type ");
        if (location.startsWith("variable ") && ofType > 0) {
            // Where a null check narrowed the value, its declared type still has `?`; a member is looked up without it.
            return "`" + location.substring("variable ".length(), ofType) + "` of type `"
                    + axlType(location.substring(ofType + " of type ".length())).replaceFirst("\\?$", "") + "`";
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

    static String axlType(String javaType) {
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

    /** The message for a pipe over a value that is not a collection; an array of objects can be wrapped in a list. */
    private static String pipeSubject(String javaType, String axlText) {
        String found = PIPE_SUBJECT + ", found `" + axlType(javaType) + "`";
        if (!javaType.endsWith("[]") || PRIMITIVE_ARRAY.matcher(javaType).matches()) {
            return found;
        }
        Matcher subject = PIPE.matcher(axlText);
        boolean named = subject.find() && SIMPLE_NAME.matcher(subject.group(1)).matches();
        return found + "; an array is not a collection, so wrap it in `List.of(" + (named ? subject.group(1) : "...") + ")`";
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
