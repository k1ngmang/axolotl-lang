package org.axl.compiler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

/** Reads the classes a class loader provides, by reflection, without initializing them. */
public final class ReflectedClasses implements JavaClasses {

    /** The classes the translator runs with: those of the JDK. */
    public static final ReflectedClasses JDK = new ReflectedClasses(ReflectedClasses.class.getClassLoader());

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
            return new ClassShape(type.isInterface(), typeParameters, supertypes, components, permitted);
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

    /** A type variable of the class, not one of the method: a method's `R` takes no `Int`. */
    private static boolean ofClass(Type type) {
        return type instanceof TypeVariable<?> variable && variable.getGenericDeclaration() instanceof Class;
    }
}
