package org.axl.javac;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Error Prone findings in Axolotl's words, without the check's `[Tag]` and without Java advice. */
final class ErrorProneMessages {

    static final String BOXED_EQUALITY = "`==` compares these numbers as objects, not by value, since they may be null "
            + "or come from a collection; use `java.util.Objects.equals(a, b)`";

    private static final Pattern TAG = Pattern.compile("^\\[(\\w+)] ");
    private static final Pattern DIVISION_BY_ZERO = Pattern.compile("[/%]\\s*0[lL]?\\b(?!\\.)");
    private static final Pattern IDENTITY = Pattern.compile("^\\[IdentityBinaryExpression] .*equivalent to `(.+?)`");
    private static final Pattern OBJECT_TO_STRING = Pattern.compile("^\\[ObjectToString] ([\\w.$]+) is final and does not override");
    private static final Pattern EQUALS_TYPES = Pattern.compile("^\\[EqualsIncompatibleType] .*incompatible types (.+) and (.+)$");
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
    private static final Pattern SELF_ASSIGNED = Pattern.compile("^\\s*(\\w+) = \\1;");

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
        return reworded != null ? reworded : TAG.matcher(message).replaceFirst("");
    }

    /** Findings whose Java words, such as override and reference equality, have plain Axolotl advice. */
    private static String reworded(String message, String text) {
        return switch (tag(message)) {
            case "ReferenceEquality" -> "`==` tests whether both sides are the same object, not whether they are equal; "
                    + "compare them with `a.equals(b)`";
            case "BoxedPrimitiveEquality" -> BOXED_EQUALITY;
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
            case "NonApiType" -> "a parameter or result typed as a class such as `HashSet` ties callers to it; use its "
                    + "interface, such as `Set`, `List` or `Map`";
            case "ReturnAtTheEndOfVoidFunction" -> "the `return` at the end of this function is unnecessary";
            default -> identityOrToString(message);
        };
    }

    private static String identityOrToString(String message) {
        Matcher identity = IDENTITY.matcher(message);
        if (identity.find()) {
            return "both sides of this expression are the same, so its value is always `" + identity.group(1) + "`";
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
                yield (created.find() ? "`" + created.group(1) + "`" : "this collection")
                        + " is filled but never read; use its values or remove it";
            }
            case "JdkObsolete" -> obsolete(message);
            case "MathAbsoluteNegative" -> "`Math.abs` of the smallest `Int` is still negative; for a remainder that is "
                    + "never negative, use `Math.floorMod(x, n)`";
            case "MissingCasesInEnumSwitch" -> {
                Matcher cases = MISSING_CASES.matcher(message);
                yield cases.find() ? "this `when` has no branch for " + constants(cases.group(1))
                        + "; add the missing branches or an `else ->` branch" : null;
            }
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
            return "`ArrayList` or `ArrayDeque` is almost always faster than `LinkedList`; use one of them";
        }
        if (message.contains("Stack is a nonstandard class")) {
            return "`Stack` is an old class; use `ArrayDeque`, which has `push`, `pop` and `peek` too";
        }
        Matcher synchronizes = SYNCHRONIZED.matcher(message);
        return synchronizes.find() ? "`" + synchronizes.group(1) + "` locks on every call, which is rarely needed; use `"
                + synchronizes.group(2) + "`" : null;
    }

    /** Findings about types, which name them as Axolotl does. */
    private static String withAxolotlTypes(String message) {
        Matcher equalsTypes = EQUALS_TYPES.matcher(message);
        if (equalsTypes.find()) {
            return "`" + Messages.axlType(equalsTypes.group(1)) + "` and `" + Messages.axlType(equalsTypes.group(2))
                    + "` are never equal, so this `equals` is always false";
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
}
