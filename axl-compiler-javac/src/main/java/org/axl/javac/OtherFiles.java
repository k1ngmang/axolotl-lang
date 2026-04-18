package org.axl.javac;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewords messages about another .axl file of the build, which only the whole build can tell apart. */
final class OtherFiles {

    private static final Pattern NO_MEMBER = Pattern.compile("^`(\\w+)` has no function or value `(\\w+)`$");
    private static final Pattern UNKNOWN_IMPORT = Pattern.compile("^unknown type `((?:[a-z]\\w*\\.)*)(\\w+)`; check the spelling$");

    private static final Pattern UNKNOWN_FUNCTION = Pattern.compile(
            "^(unknown function `(\\w+)\\(.*?\\)`); check the spelling or add `import fn`$");

    private final Collection<GeneratedSource> sources;

    OtherFiles(Collection<GeneratedSource> sources) {
        this.sources = sources;
    }

    String reword(String message) {
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
        Matcher unknownImport = UNKNOWN_IMPORT.matcher(message);
        if (unknownImport.find()) {
            String declaring = declaring(unknownImport.group(1), unknownImport.group(2));
            if (declaring != null) {
                String name = unknownImport.group(2);
                return "`" + name + "` is declared in " + declaring + ".axl: `import " + declaring.replace('/', '.') + "."
                        + name + "`";
            }
        }
        return message;
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
