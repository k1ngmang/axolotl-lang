package org.axl.compiler;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A name to use instead of one that Java reserves, added to the checker's message about it. */
final class ReservedNames {

    private static final Pattern RESERVED = Pattern.compile("^`(\\w+)` cannot be used as a name: Java, which the code becomes, reserves it$");

    private static final Map<String, String> OTHER_NAMES = Map.ofEntries(
            Map.entry("abstract", "isAbstract"), Map.entry("assert", "check"), Map.entry("boolean", "flag"),
            Map.entry("byte", "byteValue"), Map.entry("case", "choice"), Map.entry("catch", "caught"),
            Map.entry("char", "ch"), Map.entry("class", "kind"), Map.entry("const", "constant"),
            Map.entry("default", "fallback"), Map.entry("do", "action"), Map.entry("double", "amount"),
            Map.entry("enum", "kind"), Map.entry("extends", "parent"), Map.entry("final", "isFinal"),
            Map.entry("finally", "last"), Map.entry("float", "amount"), Map.entry("goto", "target"),
            Map.entry("implements", "interfaces"), Map.entry("import", "imported"), Map.entry("instanceof", "isInstance"),
            Map.entry("int", "number"), Map.entry("interface", "api"), Map.entry("long", "number"),
            Map.entry("native", "isNative"), Map.entry("new", "newValue"), Map.entry("package", "packageName"),
            Map.entry("protected", "isProtected"), Map.entry("public", "isPublic"), Map.entry("return", "result"),
            Map.entry("sealed", "isSealed"), Map.entry("short", "number"), Map.entry("static", "isStatic"),
            Map.entry("super", "parent"), Map.entry("switch", "toggle"), Map.entry("synchronized", "isSynchronized"),
            Map.entry("this", "self"), Map.entry("throw", "error"), Map.entry("throws", "errors"),
            Map.entry("transient", "isTransient"), Map.entry("try", "tries"), Map.entry("var", "variable"),
            Map.entry("void", "nothing"), Map.entry("volatile", "isVolatile"), Map.entry("yield", "result"));

    private ReservedNames() {}

    /** The diagnostics with a name to choose added to each message about a reserved name that has one. */
    static void suggest(List<AxlDiagnostic> diagnostics) {
        diagnostics.replaceAll(d -> {
            Matcher reserved = d.code() == ErrorCode.RESERVED_NAME ? RESERVED.matcher(d.message()) : null;
            String other = reserved != null && reserved.find() ? OTHER_NAMES.get(reserved.group(1)) : null;
            return other == null ? d : new AxlDiagnostic(d.file(), d.line(), d.column(), d.severity(), d.code(),
                    d.message() + "; choose another name, such as `" + other + "`");
        });
    }
}
