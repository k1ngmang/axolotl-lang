package org.axl.javac;

import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.axl.compiler.JavaClasses;

/**
 * Reads the classes of the JDK, of the jars and class directories of a classpath, and of Java sources
 * through javac, which opens them as it does for a compilation. The compiler starts with the first
 * question and is released by {@link #close}, or after each question when it is made by {@link #perQuestion}.
 */
public final class JavacClasses implements JavaClasses, AutoCloseable {

    private final List<Path> classpath;
    private final List<Path> sourcepath;
    private final Map<String, ClassShape> shapes = new HashMap<>();
    private final Map<String, List<MethodShape>> methods = new HashMap<>();
    private final Map<String, List<Signature>> signatures = new HashMap<>();
    private final boolean keepOpen;
    private StandardJavaFileManager files;
    private Elements elements;

    /** sourcepath: the directories with the Java sources the code uses. */
    public JavacClasses(List<Path> classpath, List<Path> sourcepath) {
        this(classpath, sourcepath, true);
    }

    /** For a holder that cannot close it: the compiler is released after each answer, which it then starts again. */
    public static JavacClasses perQuestion(List<Path> classpath, List<Path> sourcepath) {
        return new JavacClasses(classpath, sourcepath, false);
    }

    private JavacClasses(List<Path> classpath, List<Path> sourcepath, boolean keepOpen) {
        this.keepOpen = keepOpen;
        this.classpath = new ArrayList<>(RuntimeClasspath.entries());
        this.classpath.addAll(classpath);
        this.sourcepath = sourcepath.stream().filter(Files::isDirectory).toList();
    }

    @Override
    public synchronized ClassShape shape(String qualifiedName) {
        if (!shapes.containsKey(qualifiedName)) {
            shapes.put(qualifiedName, read(qualifiedName));
        }
        return shapes.get(qualifiedName);
    }

    @Override
    public synchronized List<MethodShape> methods(String qualifiedName) {
        if (!methods.containsKey(qualifiedName)) {
            methods.put(qualifiedName, readMethods(qualifiedName));
        }
        return methods.get(qualifiedName);
    }

    @Override
    public synchronized List<Signature> signatures(String qualifiedName, String name) {
        String key = qualifiedName + "#" + name;
        if (!signatures.containsKey(key)) {
            signatures.put(key, readSignatures(qualifiedName, name));
        }
        return signatures.get(key);
    }

    @Override
    public synchronized void close() throws IOException {
        if (files != null) {
            files.close();
            files = null;
            elements = null;
        }
    }

    private ClassShape read(String qualifiedName) {
        try {
            TypeElement type = typeNamed(qualifiedName);
            if (type == null) {
                return null;
            }
            List<String> typeParameters = type.getTypeParameters().stream().map(p -> p.getSimpleName().toString()).toList();
            List<Supertype> supertypes = new ArrayList<>();
            for (TypeMirror interfaceType : type.getInterfaces()) {
                supertypes.add(supertype(interfaceType));
            }
            if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
                supertypes.add(supertype(type.getSuperclass()));
            }
            List<String> components = new ArrayList<>();
            if (type.getKind() == ElementKind.RECORD) {
                for (RecordComponentElement component : type.getRecordComponents()) {
                    components.add(component.getSimpleName().toString());
                }
            }
            List<String> permitted = new ArrayList<>();
            for (TypeMirror subclass : type.getPermittedSubclasses()) {
                if (subclass instanceof DeclaredType declared) {
                    permitted.add(((TypeElement) declared.asElement()).getQualifiedName().toString());
                }
            }
            List<String> constants = type.getEnclosedElements().stream().filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                    .map(e -> e.getSimpleName().toString()).toList();
            return new ClassShape(type.getKind().isInterface(), typeParameters, supertypes, components, permitted, constants);
        } catch (RuntimeException e) {
            // A class file javac cannot complete.
            return null;
        } finally {
            release();
        }
    }

    private List<MethodShape> readMethods(String qualifiedName) {
        try {
            TypeElement type = typeNamed(qualifiedName);
            if (type == null || !type.getKind().isInterface()) {
                return List.of();
            }
            List<MethodShape> shapes = new ArrayList<>();
            for (Element member : elements.getAllMembers(type)) {
                // The members of Object are in the list of an interface as well.
                if (member instanceof ExecutableElement method && method.getKind() == ElementKind.METHOD
                        && !method.getModifiers().contains(Modifier.STATIC)
                        && method.getEnclosingElement().getKind().isInterface()) {
                    List<Boolean> parameters = method.getParameters().stream().map(p -> ofInterface(p.asType())).toList();
                    shapes.add(new MethodShape(method.getSimpleName().toString(), parameters,
                            method.getReturnType().getKind() == TypeKind.TYPEVAR));
                }
            }
            return shapes;
        } catch (RuntimeException e) {
            return List.of();
        } finally {
            release();
        }
    }

    private List<Signature> readSignatures(String qualifiedName, String name) {
        try {
            TypeElement type = typeNamed(qualifiedName);
            if (type == null) {
                return List.of();
            }
            boolean constructors = name.equals("<init>");
            List<? extends Element> members = constructors ? type.getEnclosedElements() : elements.getAllMembers(type);
            List<Signature> found = new ArrayList<>();
            for (Element member : members) {
                if (member instanceof ExecutableElement executable && member.getModifiers().contains(Modifier.PUBLIC)
                        && (constructors ? member.getKind() == ElementKind.CONSTRUCTOR
                                : member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name))) {
                    List<String> parameters = executable.getParameters().stream().map(p -> erased(p.asType())).toList();
                    found.add(new Signature(parameters, executable.isVarArgs(),
                            !constructors && executable.getReturnType().getKind() == TypeKind.VOID));
                }
            }
            return found;
        } catch (RuntimeException e) {
            return List.of();
        } finally {
            release();
        }
    }

    private TypeElement typeNamed(String qualifiedName) {
        if (elements == null) {
            open();
        }
        return elements.getTypeElement(qualifiedName);
    }

    private void release() {
        if (!keepOpen) {
            try {
                close();
            } catch (IOException e) {
                // The answer is read; a jar that does not close again changes nothing for it.
            }
        }
    }

    private void open() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-Xlint:none", "-classpath", join(classpath)));
        if (!sourcepath.isEmpty()) {
            options.addAll(List.of("-sourcepath", join(sourcepath)));
        }
        // Errors in the sources that are read only show where their own compilation reports them.
        JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostic -> {}, options, null, List.of());
        elements = task.getElements();
    }

    /** The supertype with, where it is written with a type parameter of the class, the name of that parameter. */
    private static Supertype supertype(TypeMirror type) {
        DeclaredType declared = (DeclaredType) type;
        List<String> arguments = new ArrayList<>();
        for (TypeMirror argument : declared.getTypeArguments()) {
            arguments.add(argument instanceof TypeVariable variable ? variable.asElement().getSimpleName().toString() : "");
        }
        return new Supertype(((TypeElement) declared.asElement()).getQualifiedName().toString(), arguments);
    }

    private static String erased(TypeMirror type) {
        if (type instanceof ArrayType array) {
            return erased(array.getComponentType()) + "[]";
        }
        if (type instanceof TypeVariable variable) {
            return variable.asElement().getSimpleName().toString();
        }
        if (type instanceof DeclaredType declared) {
            return ((TypeElement) declared.asElement()).getQualifiedName().toString();
        }
        return type.toString();
    }

    /** A type variable of the interface, not one of the method: a method's `R` takes no `Int`. */
    private static boolean ofInterface(TypeMirror type) {
        return type instanceof TypeVariable variable && variable.asElement() instanceof TypeParameterElement parameter
                && parameter.getGenericElement() instanceof TypeElement;
    }

    private static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
