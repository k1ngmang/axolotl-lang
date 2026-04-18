package org.axl.javac;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewords messages about another .axl file of the build, which only the whole build can tell apart. */
final class OtherFiles {

    private static final Pattern NO_MEMBER = Pattern.compile("^`(\\w+)` has no function or value `(\\w+)`$");
    private static final Pattern UNKNOWN_IMPORT = Pattern.compile("^unknown type `((?:[a-z]\\w*\\.)*)(\\w+)`; check the spelling$");

    private final Collection<GeneratedSource> sources;

    OtherFiles(Collection<GeneratedSource> sources) {
        this.sources = sources;
    }

    String reword(String message) {
        Matcher noMember = NO_MEMBER.matcher(message);
        if (noMember.find() && isPrivate(noMember.group(1), noMember.group(2))) {
            return "`" + noMember.group(2) + "` is private to `" + noMember.group(1) + "` and can only be used in that file";
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
