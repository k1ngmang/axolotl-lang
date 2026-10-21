package org.axl.compiler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads the classes a class loader provides, by reflection, without initializing them. */
public final class ReflectedClasses implements JavaClasses {

    /** The classes the translator runs with: those of the JDK. */
    public static final ReflectedClasses JDK = new ReflectedClasses(ReflectedClasses.class.getClassLoader());

    // Annotations that say whether a parameter takes null.
    private static final Set<String> NON_NULL = Set.of("NonNull", "NotNull", "Nonnull");
    private static final Set<String> NULLABLE = Set.of("Nullable", "CheckForNull");

    private final ClassLoader loader;

    public ReflectedClasses(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public ClassShape shape(String qualifiedName) {
        Class<?> type = load(qualifiedName);
        if (type == null) {
            return null;
        }
        try {
            List<String> typeParameters = new ArrayList<>();
            for (TypeVariable<?> variable : type.getTypeParameters()) {
                typeParameters.add(variable.getName());
            }
            List<Supertype> supertypes = new ArrayList<>();
            for (Type supertype : type.getGenericInterfaces()) {
                supertypes.add(supertype(supertype));
            }
            if (type.getGenericSuperclass() != null) {
                supertypes.add(supertype(type.getGenericSuperclass()));
            }
            List<String> components = new ArrayList<>();
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    components.add(component.getName());
                }
            }
            List<String> permitted = new ArrayList<>();
            if (type.isSealed()) {
                for (Class<?> subclass : type.getPermittedSubclasses()) {
                    permitted.add(subclass.getCanonicalName());
                }
            }
            List<String> constants = new ArrayList<>();
            if (type.isEnum()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.isEnumConstant()) {
                        constants.add(field.getName());
                    }
                }
            }
            return new ClassShape(type.isInterface(), typeParameters, supertypes, components, permitted, constants);
        } catch (LinkageError | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException e) {
            return null;
        }
    }

    @Override
    public List<MethodShape> methods(String qualifiedName) {
        List<MethodShape> shapes = new ArrayList<>();
        Class<?> type = load(qualifiedName);
        if (type == null || !type.isInterface()) {
            return shapes;
        }
        try {
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                List<Boolean> parameters = new ArrayList<>();
                for (Type parameter : method.getGenericParameterTypes()) {
                    parameters.add(ofClass(parameter));
                }
                shapes.add(new MethodShape(method.getName(), parameters, method.getGenericReturnType() instanceof TypeVariable));
            }
        } catch (LinkageError | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException e) {
            return new ArrayList<>();
        }
        return shapes;
    }

    @Override
    public List<Signature> signatures(String qualifiedName, String name) {
        List<Signature> signatures = new ArrayList<>();
        Class<?> type = load(qualifiedName);
        if (type == null) {
            return signatures;
        }
        try {
            List<Executable> executables = new ArrayList<>();
            if (name.equals("<init>")) {
                executables.addAll(List.of(type.getConstructors()));
            } else {
                for (Method method : type.getMethods()) {
                    if (method.getName().equals(name)) {
                        executables.add(method);
                    }
                }
            }
            for (Executable executable : executables) {
                List<String> parameters = new ArrayList<>();
                for (Type parameter : executable.getGenericParameterTypes()) {
                    parameters.add(erased(parameter));
                }
                signatures.add(new Signature(parameters, executable.isVarArgs(),
                        executable instanceof Method method && method.getReturnType() == void.class));
            }
        } catch (LinkageError | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException e) {
            return new ArrayList<>();
        }
        return signatures;
    }

    @Override
    public String result(String qualifiedName, String name, int arity) {
        Class<?> type = load(qualifiedName);
        if (type == null) {
            return null;
        }
        try {
            String result = null;
            for (Method method : type.getMethods()) {
                int count = method.getParameterCount();
                if (!method.getName().equals(name) || method.isBridge()
                        || count != arity && !(method.isVarArgs() && arity >= count - 1)) {
                    continue;
                }
                if (method.isVarArgs() || method.getGenericReturnType() instanceof TypeVariable<?>) {
                    return null;
                }
                String found = method.getReturnType().getCanonicalName();
                if (result != null && !result.equals(found)) {
                    return null;
                }
                result = found;
            }
            return result;
        } catch (LinkageError | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException e) {
            return null;
        }
    }

    @Override
    public String validated(String qualifiedName, String name, int arity) {
        Class<?> type = load(qualifiedName);
        if (type == null) {
            return null;
        }
        try {
            String result = null;
            for (Method method : type.getMethods()) {
                int count = method.getParameterCount();
                if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())
                        || count != arity && !(method.isVarArgs() && arity >= count - 1)) {
                    continue;
                }
                String found = count == 0 ? null : validatedType(method);
                if (found == null || result != null && !result.equals(found)) {
                    return null;
                }
                result = found;
            }
            return result;
        } catch (LinkageError | TypeNotPresentException | java.lang.reflect.MalformedParameterizedTypeException e) {
            return null;
        }
    }

    /** The class a method returns when that is the type of its first parameter, "" for a type variable, or null. */
    private static String validatedType(Method method) {
        Type parameter = method.getGenericParameterTypes()[0];
        Type returned = method.getGenericReturnType();
        List<Annotation> taking = new ArrayList<>(List.of(method.getParameterAnnotations()[0]));
        taking.addAll(List.of(method.getAnnotatedParameterTypes()[0].getAnnotations()));
        List<Annotation> giving = new ArrayList<>(List.of(method.getAnnotations()));
        giving.addAll(List.of(method.getAnnotatedReturnType().getAnnotations()));
        // In a null-marked class a parameter without @Nullable does not take null.
        boolean takesNull = named(taking, NULLABLE) || !named(taking, NON_NULL) && !nullMarked(method.getDeclaringClass());
        if (!takesNull || named(giving, Set.of("Nullable"))) {
            return null;
        }
        if (parameter instanceof TypeVariable<?> taken && returned instanceof TypeVariable<?> given) {
            return taken.equals(given) ? "" : null;
        }
        return parameter instanceof Class<?> taken && taken.equals(returned) && !taken.isPrimitive() ? taken.getCanonicalName() : null;
    }

    private static boolean named(List<Annotation> annotations, Set<String> names) {
        return annotations.stream().anyMatch(a -> names.contains(a.annotationType().getSimpleName()));
    }

    private static boolean nullMarked(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (named(List.of(c.getAnnotations()), Set.of("NullMarked"))) {
                return true;
            }
        }
        return type.getPackage() != null && named(List.of(type.getPackage().getAnnotations()), Set.of("NullMarked"));
    }

    /** A canonical name stands for the binary name `lib.Options$Some` as well as `lib.Options.Some`. */
    private Class<?> load(String qualifiedName) {
        String name = qualifiedName;
        while (true) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    return null;
                }
                name = name.substring(0, dot) + "$" + name.substring(dot + 1);
            }
        }
    }

    private static Supertype supertype(Type type) {
        List<String> arguments = new ArrayList<>();
        Class<?> raw;
        if (type instanceof ParameterizedType parameterized) {
            raw = (Class<?>) parameterized.getRawType();
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(argument instanceof TypeVariable<?> variable ? variable.getName() : "");
            }
        } else {
            raw = (Class<?>) type;
        }
        return new Supertype(raw.getCanonicalName(), arguments);
    }

    private static String erased(Type type) {
        if (type instanceof GenericArrayType array) {
            return erased(array.getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            return ((Class<?>) parameterized.getRawType()).getCanonicalName();
        }
        return type instanceof Class<?> c ? c.getCanonicalName() : type.getTypeName();
    }

    /** A type variable of the class, not one of the method: a method's `R` takes no `Int`. */
    private static boolean ofClass(Type type) {
        return type instanceof TypeVariable<?> variable && variable.getGenericDeclaration() instanceof Class;
    }
}
