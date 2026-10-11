package org.axl.javac;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Error Prone findings in Axolotl's words, without the check's `[Tag]` and without Java advice. */
final class ErrorProneMessages {

    static final String NEVER_NULL = " is never null; remove `!!`";

    static final String CLASS_TYPE = "this type is a class, which ties callers to it; ";

    static final String FILLED_NOT_READ = " is filled but never read; use its values or remove it";

    private static final String LINKED_LIST = "`ArrayList` or `ArrayDeque` is almost always faster than `LinkedList`; "
            + "use one of them";
    private static final String STACK = "`Stack` is an old class; use `ArrayDeque`, which has `push`, `pop` and `peek` too";
    private static final String LOCKS = " locks on every call, which is rarely needed; use `";

    static final String REFERENCE_EQUALITY = "`==` tests whether both sides are the same object, not whether they are equal; "
            + "compare them with `a.equals(b)`";
    static final String BOXED_EQUALITY = "`==` compares these numbers as objects, not by value, since they may be null; "
            + "use `java.util.Objects.equals(a, b)`";
    static final String BOXED_CHARACTER_EQUALITY = "`==` compares these characters as objects, not by value, since they may be "
            + "null; use `java.util.Objects.equals(a, b)`";
    /** What CharacterEquality adds to the finding about boxed values when both are characters. */
    static final String CHARACTER_OPERANDS = "Both operands are characters. ";

    /** The check that evaluates a call with constant arguments, such as `LocalDate.parse("2026-13-40")`. */
    static final String ALWAYS_THROWS = "AlwaysThrows";

    private static final Pattern TAG = Pattern.compile("^\\[(\\w+)] ");
    private static final Pattern ALWAYS_FAILS = Pattern.compile("^\\[AlwaysThrows] This call will fail at runtime with an? (\\w+)(?:: (.*))?$");
    private static final Pattern DIVISION_BY_ZERO = Pattern.compile("[/%]\\s*0[lL]?\\b(?!\\.)");
    private static final Pattern IDENTITY = Pattern.compile("^\\[IdentityBinaryExpression] .*equivalent to `(.+?)`");
    private static final Pattern OBJECT_TO_STRING = Pattern.compile("^\\[ObjectToString] ([\\w.$]+) is final and does not override");
    // Types with the same class but other type arguments get a sentence more, "They are incompatible because ...".
    private static final Pattern EQUALS_TYPES = Pattern.compile(
            "^\\[EqualsIncompatibleType] .*incompatible types (.+?) and (.+?)(\\. They are incompatible because .*)?$");
    private static final Pattern COLLECTION_TYPES = Pattern.compile(
            "^\\[CollectionIncompatibleType] Argument '(.+?)' .*its type (.+?) is not compatible with its collection's type argument (.+)$");
    private static final Pattern FORMAT_TYPE = Pattern.compile("'([\\w.$]+)' cannot be formatted using '(.+?)'");
    private static final Pattern FORMAT_COUNT = Pattern.compile("extra format arguments: used (\\d+), provided (\\d+)");
    private static final Pattern MISSING_FORMAT_ARGUMENT = Pattern.compile("missing argument for format specifier '(.+?)'");
    private static final Pattern NEVER_READ = Pattern.compile(
            "^\\[UnusedVariable] The (?:local variable|parameter|field) '(\\w+)' is never read");
    private static final Pattern ASSIGNMENT_NEVER_READ = Pattern.compile("^\\[UnusedVariable] This assignment to the local variable '(\\w+)' is never read");
    private static final Pattern NEVER_USED = Pattern.compile("^\\[UnusedMethod] Method '(\\w+)' is never used");
    private static final Pattern SYNCHRONIZED = Pattern.compile("^\\[JdkObsolete] (\\w+) performs synchronization .*prefer (\\w+)");
    private static final Pattern MISSING_CASES = Pattern.compile("^\\[MissingCasesInEnumSwitch] .*handle the remaining cases: (.+)$");
    private static final Pattern CREATED = Pattern.compile("\\b(\\w+) = new ");
    private static final Pattern DECLARED = Pattern.compile("\\bvar (\\w+) = ");
    private static final Pattern SELF_ASSIGNED = Pattern.compile("^\\s*(\\w+) = \\1;");
    private static final Pattern NOT_NULL_ARGUMENT = Pattern.compile("requireNonNull\\((\"[^\"]*\"|[\\w.]+)\\)");
    private static final Pattern CREATED_EXCEPTION = Pattern.compile("\\bnew (?:[a-z]\\w*\\.)*(\\w+)\\(");
    private static final Pattern OUT_OF_RANGE = Pattern.compile(
            "^\\[ComparisonOutOfRange] (\\w+?)s may have a value in the range (\\S+) to (\\S+); therefore, this comparison to "
            + "(\\S+) will always evaluate to (\\w+)");
    private static final Pattern SENTENCE_START = Pattern.compile("(?:This|The|A|An|It|Use|Using|Calling|Consider|Prefer|"
            + "Avoid|Do|Don't|Expected|Found|Method)\\b");
    private static final Pattern ALWAYS = Pattern.compile("always evaluates to `(\\w+)`");
    private static final Pattern CALL = Pattern.compile("\\w\\(");
    private static final Pattern INTERFACE = Pattern.compile("java\\.\\w+\\.(\\w+)");
    // `java.util.List<java.lang.Integer>` in a finding, which Axolotl writes `List<Int>`.
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("\\b(?:[a-z]\\w*\\.)+[A-Z][\\w$]*(?:<\\S*>)?");
    private static final Pattern SUPPRESSION = Pattern.compile(" \\(Error Prone check \\w+; @SuppressWarnings\\(\"\\w+\"\\) turns it off\\)$");

    private ErrorProneMessages() {}

    /** message has no link or suggestion any more; text is the Java line it is about. */
    static String translate(String message, String text) {
        String reworded = reworded(message, text);
        if (reworded == null) {
            reworded = warning(message, text);
        }
        if (reworded == null) {
            reworded = withAxolotlTypes(message);
        }
        if (reworded == null) {
            reworded = mistake(message, text);
        }
        return reworded != null ? reworded : plain(message);
    }

    /**
     * A finding without a rewording of its own, with less of Java's notation: `String#trim` and `#trim`
     * become `trim`, a method a function.
     */
    private static String plain(String message) {
        String named = QUALIFIED_TYPE.matcher(message)
                .replaceAll(m -> Matcher.quoteReplacement(Messages.simpleNames(Messages.axlType(m.group()))));
        String text = TAG.matcher(named).replaceFirst("").replaceAll("\\b[A-Z]\\w*#(\\w+)", "$1").replace("#", "")
                .replaceAll("\\bmethod\\b", "function").replaceAll("\\bmethods\\b", "functions")
                .replaceAll("\\bInteger\\b", "Int").replaceFirst("\\.$", "");
        // A sentence starts lowercase in Axolotl's messages, but a class name keeps its capital.
        Matcher first = SENTENCE_START.matcher(text);
        return first.lookingAt() ? first.group().toLowerCase(java.util.Locale.ROOT) + text.substring(first.end()) : text;
    }

    /** Findings about code that does not do what it seems to, with the way to write what was meant. */
    private static String mistake(String message, String text) {
        return switch (tag(message)) {
            case "EqualsNull" -> "`equals(null)` is always false, and fails when the value itself is null; test with `== null`";
            case "UnnecessaryCheckNotNull", "ImpossibleNullComparison" -> {
                Matcher argument = NOT_NULL_ARGUMENT.matcher(text);
                yield argument.find() ? "`" + argument.group(1) + "`" + NEVER_NULL : "this value" + NEVER_NULL;
            }
            case "DeadException" -> {
                Matcher created = CREATED_EXCEPTION.matcher(text);
                yield "the exception is created but not thrown; write `throw " + (created.find() ? created.group(1) : "E")
                        + "(...)`";
            }
            case "InfiniteRecursion" -> "this function always calls itself, so it never ends; return without the call "
                    + "in some case";
            case "MathRoundIntLong" -> "`Math.round` of a whole number turns it into a `Float` first, which can change "
                    + "large values; remove `Math.round`";
            case "CompareToZero" -> "compare the result of `compareTo` with 0, as in `a.compareTo(b) > 0`; it need not be "
                    + "1 or -1";
            case "ListRemoveAmbiguous" -> "on a list of `Int`, `remove(i)` removes the element at index `i`, not the value "
                    + "`i`; to remove a value, write `remove(Integer.valueOf(i))`";
            case "NullNeedsCastForVarargs" -> "a lone `null` here is taken as the whole list of values, which fails when "
                    + "the call runs; keep it in a typed value first, such as `val none: String? = null`";
            case "SubstringOfZero" -> "`substring(0)` gives the same string; remove it";
            case ALWAYS_THROWS -> alwaysFails(message, text);
            default -> arrayOrOldApi(message);
        };
    }

    /** A call with constant arguments that always throws; inside `attempt` the failure is handled, so it is a warning. */
    private static String alwaysFails(String message, String text) {
        Matcher fails = ALWAYS_FAILS.matcher(message);
        if (!fails.find()) {
            return null;
        }
        String reason = fails.group(2) == null ? "" : ": " + fails.group(2).replaceFirst("\\.$", "");
        String always = "this call always fails with `" + fails.group(1) + "`" + reason;
        return Messages.Attempt.in(text) != null ? always + "; `attempt` catches it, so its `else` branch always runs"
                : always + "; fix the value, or handle the failure with `attempt ... else ...`";
    }

    private static String arrayOrOldApi(String message) {
        return switch (tag(message)) {
            case "ArrayHashCode" -> "`hashCode` of a Java array does not look at its elements; use "
                    + "`java.util.Arrays.hashCode(a)`";
            case "ArrayToString" -> "`toString` of a Java array shows only its type and address; use "
                    + "`java.util.Arrays.toString(a)`, or `String(chars)` for characters";
            case "ArrayEquals" -> "`equals` of Java arrays tests whether both are the same array, not their elements; use "
                    + "`java.util.Arrays.equals(a, b)`";
            case "DefaultCharset" -> "this uses the computer's default character set, which differs between computers; "
                    + "pass one, such as `java.nio.charset.StandardCharsets.UTF_8`";
            case "MisusedWeekYear" -> "`YYYY` in a date pattern is the year of the week, which differs around New Year; "
                    + "write `yyyy`";
            case "JavaUtilDate", "JavaUtilDateChecker" -> "`Date` is an old class that leads to bugs; use `java.time.Instant` or `LocalDate`";
            default -> outOfRange(message);
        };
    }

    private static String outOfRange(String message) {
        Matcher range = OUT_OF_RANGE.matcher(message);
        if (!range.find()) {
            return null;
        }
        String primitive = range.group(1);
        String type = primitive.equals("char") ? "Character" : primitive.equals("int") ? "Int"
                : Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1);
        return "a `" + type + "` holds values from " + range.group(2) + " to " + range.group(3) + ", so this comparison with "
                + range.group(4) + " is always " + range.group(5);
    }

    /** Findings whose Java words, such as override and reference equality, have plain Axolotl advice. */
    private static String reworded(String message, String text) {
        return switch (tag(message)) {
            case "ReferenceEquality" -> REFERENCE_EQUALITY;
            case "BoxedPrimitiveEquality" -> message.contains(CHARACTER_OPERANDS) ? BOXED_CHARACTER_EQUALITY : BOXED_EQUALITY;
            case "ConstantOverflow" -> DIVISION_BY_ZERO.matcher(text).find() ? "division by zero"
                    : "this calculation is too large for its type; use `Long`, with `L` after a number";
            case "EqualsHashCode" -> "a class that gives `fn equals` must also give `fn hashCode() -> Int`, so that equal "
                    + "objects have equal hash codes";
            case "NarrowingCompoundAssignment" -> "the result does not fit the type of the variable and would be cut, "
                    + "as `i += 1.7` gives 6 for an `Int`; give the variable a type that holds the result, such as `Double` "
                    + "or `Long`";
            case "OperatorPrecedence" -> "`&&` goes before `||`, and `*` before `+`; add parentheses to show which you mean, "
                    + "as in `(a && b) || c`";
            case "StringSplitter" -> "`split` takes a regular expression and drops empty strings at the end; check that "
                    + "this is what you want";
            case "NonApiType" -> {
                Matcher type = INTERFACE.matcher(message);
                yield CLASS_TYPE + (type.find() ? "use `" + type.group(1) + "` instead" : "use an interface, such as `List`");
            }
            case "ReturnAtTheEndOfVoidFunction" -> "the `return` at the end of this function is unnecessary";
            case "StreamResourceLeak" -> streamLeak(text);
            default -> identityOrToString(message, text);
        };
    }

    /** A stream of `Files.lines` or `Files.list` holds the file open; `use` closes it, as try-with-resources does. */
    private static String streamLeak(String text) {
        Matcher declared = DECLARED.matcher(text);
        Messages.Attempt attempt = Messages.Attempt.in(text);
        String call = attempt != null && !attempt.value().contains("new ") && !attempt.value().contains("->") ? attempt.value() : "...";
        return "this stream keeps its file or directory open until it is closed; open it with `use (val "
                + (declared.find() ? declared.group(1) : "lines") + " = " + call + ") { ... } else e -> throw IllegalStateException(e)`";
    }

    private static String identityOrToString(String message, String text) {
        Matcher identity = IDENTITY.matcher(message);
        if (identity.find()) {
            String always = "both sides of this expression are the same, so its value is always `" + identity.group(1) + "`";
            return CALL.matcher(text).find() ? always + " unless their calls give different values" : always;
        }
        Matcher toString = OBJECT_TO_STRING.matcher(message);
        if (toString.find()) {
            String type = Messages.simpleNames(Messages.axlType(toString.group(1)));
            return "`" + type + "` has no `toString`, so it is shown as `" + type + "@4488aabb`; give it "
                    + "`fn toString() -> String`";
        }
        return null;
    }

    /** Warnings about calculations, collections and old JDK classes. */
    private static String warning(String message, String text) {
        return switch (tag(message)) {
            case "UnusedVariable", "UnusedMethod" -> unused(message, text);
            case "NarrowCalculation", "IntLongMath" -> narrowCalculation(message);
            case "ModifiedButNotUsed" -> {
                Matcher created = CREATED.matcher(text);
                yield (created.find() ? "`" + created.group(1) + "`" : "this collection") + FILLED_NOT_READ;
            }
            case "JdkObsolete" -> obsolete(message);
            case "MathAbsoluteNegative" -> "`Math.abs` of the smallest `Int` is still negative; for a remainder that is "
                    + "never negative, use `Math.floorMod(x, n)`";
            case "MissingCasesInEnumSwitch" -> {
                Matcher cases = MISSING_CASES.matcher(message);
                yield cases.find() ? "this `when` has no branch for " + constants(cases.group(1))
                        + "; add the missing branches or an `else ->` branch" : null;
            }
            case "ComplexBooleanConstant" -> {
                Matcher always = ALWAYS.matcher(message);
                yield always.find() ? "a comparison of constants here is always `" + always.group(1) + "`; write `"
                        + always.group(1) + "` in its place" : null;
            }
            case "AlreadyChecked" -> "an `if` around this condition already checked it, so its result is known here; remove it";
            case "DuplicateBranches" -> "both branches give the same, so the condition makes no difference; keep one of them";
            case "UnnecessaryStringBuilder" -> "join strings with `+` instead of a `StringBuilder`; it reads better and is as fast";
            case "DoNotCall" -> message.contains("Calling run on Thread")
                    ? "`run` does the work on this thread; call `start` to run it on a thread of its own" : null;
            case "SelfAssignment" -> {
                Matcher self = SELF_ASSIGNED.matcher(text);
                yield (self.find() ? "`" + self.group(1) + "`" : "this variable") + " is assigned to itself, which does nothing";
            }
            default -> null;
        };
    }

    /** `RED, GREEN, and 2 others` with each constant in backticks and no comma before "and". */
    private static String constants(String list) {
        String joined = list.contains(", and ") ? list.replace(", and ", " and ") : list.replaceFirst(", (\\w+)$", " and $1");
        return joined.replaceAll("\\b([A-Z][A-Z0-9_]*)\\b", "`$1`");
    }

    private static String unused(String message, String text) {
        Matcher neverRead = NEVER_READ.matcher(message);
        if (neverRead.find()) {
            return "`" + neverRead.group(1) + "` is never read";
        }
        Matcher neverUsed = NEVER_USED.matcher(message);
        if (neverUsed.find()) {
            return "`" + neverUsed.group(1) + "` is never used";
        }
        Matcher assignment = ASSIGNMENT_NEVER_READ.matcher(message);
        if (!assignment.find()) {
            return null;
        }
        String name = assignment.group(1);
        return text.strip().startsWith(name + " =") ? "the value given to `" + name + "` here is never read"
                : "the first value of `" + name + "` is replaced before it is read; declare `" + name
                + "` where its value is known";
    }

    /** IntLongMath and NarrowCalculation both report `a * b` as a `Long`; one wording lets the second go. */
    private static String narrowCalculation(String message) {
        if (message.contains("division will discard the fractional part")) {
            return "this division of whole numbers drops the fraction before the result becomes a `Double`; "
                    + "make one side a `Double`, as in `a / 2.0`";
        }
        if (message.contains("overflow before being")) {
            return "this `Int` calculation may overflow before its result becomes a `Long`; start it with a `Long`, "
                    + "as in `1L * a * b`";
        }
        return null;
    }

    private static String obsolete(String message) {
        if (message.contains("LinkedList to out-perform")) {
            return LINKED_LIST;
        }
        if (message.contains("Stack is a nonstandard class")) {
            return STACK;
        }
        Matcher synchronizes = SYNCHRONIZED.matcher(message);
        return synchronizes.find() ? "`" + synchronizes.group(1) + "`" + LOCKS + synchronizes.group(2) + "`" : null;
    }

    /** Whether a translated message advises against an old JDK class, which says more than NonApiType. */
    static boolean isObsoleteClass(String translated) {
        return translated.equals(LINKED_LIST) || translated.equals(STACK) || translated.contains(LOCKS);
    }

    /** Findings about types, which name them as Axolotl does. */
    private static String withAxolotlTypes(String message) {
        Matcher equalsTypes = EQUALS_TYPES.matcher(message);
        if (equalsTypes.find()) {
            String reason = equalsTypes.group(3) == null ? "" : ": the type arguments differ";
            return "`" + Messages.axlType(equalsTypes.group(1)) + "` and `" + Messages.axlType(equalsTypes.group(2))
                    + "` are never equal" + reason + ", so this `equals` is always false";
        }
        Matcher collectionTypes = COLLECTION_TYPES.matcher(message);
        if (collectionTypes.find()) {
            return "`" + collectionTypes.group(1) + "` is of type `" + Messages.axlType(collectionTypes.group(2))
                    + "`, but the collection holds `" + Messages.axlType(collectionTypes.group(3))
                    + "`, so this call never finds it";
        }
        Matcher formatType = FORMAT_TYPE.matcher(message);
        if (formatType.find()) {
            return "`" + formatType.group(2) + "` cannot format a value of type `" + Messages.axlType(formatType.group(1)) + "`";
        }
        Matcher formatCount = FORMAT_COUNT.matcher(message);
        if (formatCount.find()) {
            return "the format uses " + formatCount.group(1) + " of the values after it, but " + formatCount.group(2)
                    + " are given";
        }
        Matcher missingArgument = MISSING_FORMAT_ARGUMENT.matcher(message);
        return missingArgument.find() ? "`" + missingArgument.group(1) + "` in the format has no value to show" : null;
    }

    private static String tag(String message) {
        Matcher tag = TAG.matcher(message);
        return tag.find() ? tag.group(1) : "";
    }

    /** The check behind a finding as javac gives it, such as `DefaultCharset`, or null for a message of javac itself. */
    static String check(String message) {
        Matcher tag = TAG.matcher(message);
        return tag.find() ? tag.group(1) : null;
    }

    /**
     * How a warning is turned off, for the first warning of each check in a build: the check's name
     * appears nowhere else in Axolotl's words.
     */
    static String suppression(String check) {
        return " (Error Prone check " + check + "; @SuppressWarnings(\"" + check + "\") turns it off)";
    }

    /** The message without the check named by {@link #suppression}, the way the other warnings of the check read. */
    static String withoutSuppression(String message) {
        return SUPPRESSION.matcher(message).replaceFirst("");
    }
}
