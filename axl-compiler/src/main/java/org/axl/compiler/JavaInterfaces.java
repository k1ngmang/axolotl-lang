package org.axl.compiler;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;

/**
 * The methods of a Java interface that the JDK, or the classpath of the translator, provides. A method
 * that has a type variable where an `Int` is written gets the box `Integer` in Java, not `int`.
 */
public final class JavaInterfaces {

    /**
     * @param typeVariables for each parameter, whether its type is a type variable of the interface
     * @param typeVariable  whether the result type is one
     */
    public record Shape(String name, List<Boolean> typeVariables, boolean typeVariable) {}

    private JavaInterfaces() {}

    /** The instance methods of the interface and of the interfaces it extends; empty when it is not one that can be loaded. */
    public static List<Shape> shapes(String qualifiedName) {
        List<Shape> shapes = new ArrayList<>();
        try {
            Class<?> type = Class.forName(qualifiedName, false, JavaInterfaces.class.getClassLoader());
            if (!type.isInterface()) {
                return shapes;
            }
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                List<Boolean> parameters = new ArrayList<>();
                for (java.lang.reflect.Type parameter : method.getGenericParameterTypes()) {
                    parameters.add(ofInterface(parameter));
                }
                shapes.add(new Shape(method.getName(), parameters, method.getGenericReturnType() instanceof TypeVariable));
            }
        } catch (ClassNotFoundException | LinkageError e) {
            return new ArrayList<>();
        }
        return shapes;
    }

    /** A type variable of the interface, not one of the method: a method's `R` takes no `Int`. */
    private static boolean ofInterface(java.lang.reflect.Type type) {
        return type instanceof TypeVariable<?> variable && variable.getGenericDeclaration() instanceof Class;
    }
}
