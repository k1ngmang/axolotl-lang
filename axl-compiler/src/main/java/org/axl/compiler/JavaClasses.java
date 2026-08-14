package org.axl.compiler;

import java.util.List;

/**
 * Reads classes that no file of the build declares: those of the JDK, of jars and class directories on
 * the classpath, and of Java sources. The translator asks for the type parameters of a sealed type it
 * narrows, the components of a record it takes apart, and the methods of a generic interface a class
 * implements. Names are canonical, `java.util.Map.Entry`.
 */
public interface JavaClasses {

    /**
     * A supertype of a class, with for each of its type arguments the name of the type parameter of the
     * class written there, or an empty string for any other type.
     */
    record Supertype(String name, List<String> arguments) {}

    /**
     * @param isInterface    whether it is an interface
     * @param typeParameters the names of its type parameters
     * @param supertypes     the class it extends and the interfaces it implements
     * @param components     the components of a record, empty for any other class
     * @param permitted      the classes a sealed class or interface permits, empty for any other class
     */
    record ClassShape(boolean isInterface, List<String> typeParameters, List<Supertype> supertypes,
                      List<String> components, List<String> permitted) {}

    /**
     * An instance method of an interface.
     *
     * @param typeVariables for each parameter, whether its type is a type variable of the interface
     * @param typeVariable  whether the result type is a type variable
     */
    record MethodShape(String name, List<Boolean> typeVariables, boolean typeVariable) {}

    /** The class, or null when it cannot be read. */
    ClassShape shape(String qualifiedName);

    /**
     * The instance methods of the interface and of the interfaces it extends; empty when it is not an
     * interface that can be read. A method that has a type variable where an `Int` is written gets the
     * box `Integer` in Java, not `int`.
     */
    List<MethodShape> methods(String qualifiedName);
}
