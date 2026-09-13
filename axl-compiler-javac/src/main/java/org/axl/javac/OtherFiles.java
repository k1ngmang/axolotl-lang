package org.axl.javac;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewords messages about another .axl file of the build, which only the whole build can tell apart. */
final class OtherFiles {

    private static final Pattern NO_MEMBER = Pattern.compile("^`(\\w+)` has no function or value `(\\w+)`$");
    // A declaration of the build comes before a habit or a close spelling, which the file alone suggests.
    private static final Pattern UNKNOWN_TYPE = Pattern.compile("^unknown type `(\\w+)`; ");
    private static final Pattern UNKNOWN_IMPORT =Pattern.compile("^unknown type `((?:[a-z]\\w*\\.)*)(\\w+)`; check the spelling$");
    // A type used as a value, `Level.LOW`: javac takes `Level` for a package in a `when` branch, for a name elsewhere.
    private static final Pattern UNKNOWN_NAME = Pattern.compile("^unknown (?:name|package) `(\\w+)`; ");

    private static final Pattern UNKNOWN_FUNCTION = Pattern.compile("^(unknown function `(\\w+)\\(.*?\\)`); ");

    private static final Pattern NO_FIELD = Pattern.compile("^(?:`\\w+` of type )?`(?:[\\w.]+\\.)?(\\w+)(?:<.*>)?` has no field `(\\w+)`$");
    private static final Pattern NO_METHOD = Pattern.compile("^(`(\\w+)` of type `(?:[\\w.]+\\.)?(\\w+)(?:<.*>)?` has no method `(\\w+)\\(\\)`)(?:;.*)?$");
    private static final Pattern PRIVATE_FIELD = Pattern.compile("^`(\\w+)` is private to `(?:[\\w.]+\\.)?(\\w+)` and can only be used in that file$");

    private final Collection<GeneratedSource> sources;
    private ClasspathTypes classpath = new ClasspathTypes(List.of());

    OtherFiles(Collection<GeneratedSource> sources) {
        this.sources = sources;
    }

    /** The jars and class directories whose classes an unknown name may need an import of. */
    void classpath(List<Path> entries) {
        classpath = new ClasspathTypes(entries);
    }

    String reword(String message) {
        Matcher noField = NO_FIELD.matcher(message);
        if (noField.find() && hasAccessor(noField.group(1), noField.group(2))) {
            return message + "; `" + noField.group(2) + "` is a method: `" + noField.group(2) + "()`";
        }
        Matcher noMethod = NO_METHOD.matcher(message);
        if (noMethod.find() && hasField(noMethod.group(3), noMethod.group(4))) {
            return noMethod.group(1) + "; `" + noMethod.group(4) + "` is a property of the class `" + noMethod.group(3)
                    + "`: read it without parentheses, `" + noMethod.group(2) + "." + noMethod.group(4) + "`";
        }
        Matcher privateField = PRIVATE_FIELD.matcher(message);
        if (privateField.find() && hasComponent(privateField.group(2), privateField.group(1))) {
            return "read the field `" + privateField.group(1) + "` of the data class `" + privateField.group(2)
                    + "` with parentheses: `" + privateField.group(1) + "()`";
        }
        String file = privateField.find(0) ? declaringFile(privateField.group(2)) : null;
        if (file != null) {
            return message.replaceFirst("that file$", file + ".axl");
        }
        Matcher noMember = NO_MEMBER.matcher(message);
        if (noMember.find() && isPrivate(noMember.group(1), noMember.group(2))) {
            return "`" + noMember.group(2) + "` is private to `" + noMember.group(1) + "` and can only be used in that file";
        }
        if (noMember.find(0)) {
            String similar = similarMember(noMember.group(1), noMember.group(2));
            return similar == null ? message : message + "; did you mean `" + similar + "`?";
        }
        Matcher unknownFunction = UNKNOWN_FUNCTION.matcher(message);
        if (unknownFunction.find()) {
            String declaring = declaringFunction(unknownFunction.group(2));
            return declaring == null ? message : unknownFunction.group(1) + "; it is declared in " + declaring + ".axl: "
                    + "`import fn " + declaring.replace('/', '.') + "." + unknownFunction.group(2) + "`";
        }
        Matcher unknownType = UNKNOWN_TYPE.matcher(message);
        String typeFile = unknownType.find() ? declaringFile(unknownType.group(1)) : null;
        if (typeFile != null) {
            return "unknown type `" + unknownType.group(1) + Messages.DECLARED_IN + typeFile + ".axl: `import "
                    + typeFile.replace('/', '.') + "." + unknownType.group(1) + "`";
        }
        Matcher unknownName = UNKNOWN_NAME.matcher(message);
        String nameFile = unknownName.find() ? declaringFile(unknownName.group(1)) : null;
        if (nameFile != null) {
            return "unknown name `" + unknownName.group(1) + Messages.DECLARED_IN + nameFile + ".axl: `import "
                    + nameFile.replace('/', '.') + "." + unknownName.group(1) + "`";
        }
        String valueFile = unknownName.find(0) ? declaringValue(unknownName.group(1)) : null;
        if (valueFile != null) {
            return "unknown name `" + unknownName.group(1) + Messages.DECLARED_IN + valueFile + ".axl: `import val "
                    + valueFile.replace('/', '.') + "." + unknownName.group(1) + "`";
        }
        String unknown = unknownName.find(0) ? unknownName.group(1) : unknownType.find(0) ? unknownType.group(1) : null;
        List<String> classes = unknown != null && Character.isUpperCase(unknown.charAt(0)) ? classpath.named(unknown) : List.of();
        if (!classes.isEmpty()) {
            return message.substring(0, message.indexOf("; ")) + "; " + onClasspath(classes);
        }
        Matcher unknownImport = UNKNOWN_IMPORT.matcher(message);
        if (unknownImport.find()) {
            String declaring = declaring(unknownImport.group(1), unknownImport.group(2));
            if (declaring != null) {
                String name = unknownImport.group(2);
                return "`" + name + "` is declared in " + declaring + ".axl: `import " + declaring.replace('/', '.') + "."
                        + name + "`";
            }
        }
        return Messages.outOfBoundsOf(message, this::typeParameters);
    }

    /** `grid.Grid` is on the classpath: `import grid.Grid`, or a choice of the first few classes of that name. */
    private static String onClasspath(List<String> classes) {
        if (classes.size() == 1) {
            return "`" + classes.getFirst() + "` is on the classpath: `import " + classes.getFirst() + "`";
        }
        List<String> shown = classes.stream().limit(3).map(c -> "`import " + c + "`").toList();
        return "the classpath has " + classes.size() + " classes of that name; import one of them: " + String.join(", ", shown)
                + (classes.size() > shown.size() ? ", ..." : "");
    }

    /** The type parameters of a generic type of the build, as its Java declares them, or null. */
    private String typeParameters(String type) {
        Pattern declaration = Pattern.compile("^    (?:public |private )?(?:static |sealed |final )*(?:record|class|interface) "
                + Pattern.quote(type) + "<", Pattern.MULTILINE);
        for (GeneratedSource source : sources) {
            String java = source.unit().javaSource();
            Matcher found = declaration.matcher(java);
            if (found.find()) {
                return Messages.angleArguments(java, found.end());
            }
        }
        return null;
    }

    /** Whether a type of the build has a method without parameters of this name, such as a field of a data class. */
    private boolean hasAccessor(String type, String member) {
        Pattern declaration = Pattern.compile("^    (?:public |private )?(?:static |sealed |final )*(?:record|class|interface) "
                + Pattern.quote(type) + "\\b.*$", Pattern.MULTILINE);
        Pattern accessor = Pattern.compile("\\b" + Pattern.quote(member) + "\\(\\)|[\\w>\\]] " + Pattern.quote(member) + "[,)]");
        return sources.stream().map(s -> s.unit().javaSource()).anyMatch(java -> {
            Matcher found = declaration.matcher(java);
            return found.find() && accessor.matcher(typeBody(java, found)).find();
        });
    }

    /** Whether a class of the build has a property of this name, which its Java has as a field. */
    private boolean hasField(String type, String property) {
        Pattern declaration = Pattern.compile("^    (?:public |private )?(?:static |final )*class " + Pattern.quote(type) + "\\b.*$",
                Pattern.MULTILINE);
        Pattern field = Pattern.compile("^\\s+(?:public |private )?(?:final )?[\\w.<>?, @\\[\\]]+ " + Pattern.quote(property) + ";$",
                Pattern.MULTILINE);
        return sources.stream().map(s -> s.unit().javaSource()).anyMatch(java -> {
            Matcher found = declaration.matcher(java);
            return found.find() && field.matcher(typeBody(java, found)).find();
        });
    }

    /** Whether a data class of the build has a field of this name. */
    private boolean hasComponent(String type, String field) {
        Pattern header = Pattern.compile("^    (?:public |private )?(?:static |final )*record " + Pattern.quote(type)
                + "\\b(?:<.*?>)?\\((.*)\\)", Pattern.MULTILINE);
        Pattern component = Pattern.compile("(?:^|,)[^,]*\\b" + Pattern.quote(field) + "\\s*(?:,|$)");
        return sources.stream().map(s -> header.matcher(s.unit().javaSource()))
                .anyMatch(found -> found.find() && component.matcher(found.group(1)).find());
    }

    /** The .axl path without extension of the file of the build that declares the type, or null. */
    private String declaringFile(String type) {
        Pattern nested = Pattern.compile("^    (?:public |private )?(?:static |sealed |final )*(?:record|class|interface|enum) "
                + Pattern.quote(type) + "\\b", Pattern.MULTILINE);
        return sources.stream().map(GeneratedSource::unit).filter(u -> nested.matcher(u.javaSource()).find())
                .map(u -> u.qualifiedName().replace('.', '/')).findFirst().orElse(null);
    }

    /** A public top-level value of a file of the build, given as the path of the file without extension. */
    private String declaringValue(String name) {
        Pattern declaration = Pattern.compile("^    public static (?:final )?[^=(]*\\b" + Pattern.quote(name) + "\\s*[=;]", Pattern.MULTILINE);
        return sources.stream().map(GeneratedSource::unit).filter(u -> declaration.matcher(u.javaSource()).find())
                .map(u -> u.qualifiedName().replace('.', '/')).findFirst().orElse(null);
    }

    /** The declaration line of a nested type, and its body when it has one. */
    private static String typeBody(String java, Matcher declaration) {
        if (declaration.group().endsWith("{}")) {
            return declaration.group();
        }
        int end = java.indexOf("\n    }", declaration.end());
        return java.substring(declaration.start(), end < 0 ? java.length() : end);
    }

    /** A public function of a file of the build, given as the path of the file without extension. */
    private String declaringFunction(String name) {
        Pattern declaration = Pattern.compile("^    public static [^=(]*\\b" + Pattern.quote(name) + "\\(", Pattern.MULTILINE);
        return sources.stream().map(GeneratedSource::unit).filter(u -> declaration.matcher(u.javaSource()).find())
                .map(u -> u.qualifiedName().replace('.', '/')).findFirst().orElse(null);
    }

    private String similarMember(String fileClass, String member) {
        return sources.stream().filter(s -> s.unit().className().equals(fileClass))
                .map(s -> Names.similar(member, s.unit().javaSource().split("\n"))).filter(n -> n != null)
                .findFirst().orElse(null);
    }

    private boolean isPrivate(String fileClass, String member) {
        Pattern declaration = Pattern.compile("^\\s*private static [^=(]*\\b" + Pattern.quote(member) + "\\s*[(=;]", Pattern.MULTILINE);
        return sources.stream().filter(s -> s.unit().className().equals(fileClass))
                .anyMatch(s -> declaration.matcher(s.unit().javaSource()).find());
    }

    /** The .axl path without extension of the file in the package that declares the type, such as `model/Shapes`. */
    private String declaring(String packagePrefix, String type) {
        String packageName = packagePrefix.isEmpty() ? "" : packagePrefix.substring(0, packagePrefix.length() - 1);
        Pattern nested = Pattern.compile("^    (?:public |private )?(?:static |sealed |final )*(?:record|class|interface|enum) "
                + Pattern.quote(type) + "\\b", Pattern.MULTILINE);
        return sources.stream().map(GeneratedSource::unit)
                .filter(u -> u.packageName().equals(packageName) && nested.matcher(u.javaSource()).find())
                .map(u -> u.qualifiedName().replace('.', '/')).findFirst().orElse(null);
    }
}
