package org.axl.compiler.check;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.JavaClasses.ClassShape;
import org.axl.compiler.ast.Import;
import org.axl.compiler.ast.SourceFile;

@NullMarked public final class Libraries {
    private Libraries() {}
    public record Library(String name, ClassShape shape) {
        public Library {
            Nulls.param(name, "name");
            Nulls.param(shape, "shape");
        }
    }
    public static @Nullable Library classNamed(SourceFile file, JavaClasses classes, String name) {
        Nulls.param(file, "file");
        Nulls.param(classes, "classes");
        Nulls.param(name, "name");
        for (final var candidate : candidates(file, name)) {
            final var shape = classes.shape(candidate);
            if (shape != null) {
                return new Library(candidate, shape);
            }
        }
        return null;
    }
    private static List<String> candidates(SourceFile file, String name) {
        final var dot = name.indexOf(".");
        final var head = (dot < 0 ? name : name.substring(0, dot));
        final var rest = (dot < 0 ? "" : name.substring(dot));
        final var result = new ArrayList<String>();
        for (final var i : file.imports()) {
            if (i.kind() == Import.Kind.TYPE && i.simpleName().equals(head)) {
                result.add(i.name() + rest);
                return result;
            }
        }
        if (dot >= 0) {
            result.add(name);
        }
        final var own = file.packageName();
        if (own != null) {
            result.add(own + "." + name);
        }
        result.add("java.lang." + name);
        return result;
    }
}
