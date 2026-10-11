package org.axl.compiler;

import java.util.List;

/**
 * Reads classes that no file of the build declares: those of the JDK, of jars and class directories on
 * the classpath, and of Java sources. The translator asks for the type parameters of a sealed type it
 * narrows, the components of a record it takes apart, the methods of a generic interface a class
 * implements, the parameters of the methods a list is passed to, and the cases of a sealed type or an
 * enum that a `when` without `else` must name. Names are canonical,
 * `java.util.Map.Entry`.
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
     * @param constants      the constants of an enum, empty for any other class
     */
    record ClassShape(boolean isInterface, List<String> typeParameters, List<Supertype> supertypes,
                      List<String> components, List<String> permitted, List<String> constants) {}

    /**
     * An instance method of an interface.
     *
     * @param typeVariables for each parameter, whether its type is a type variable of the interface
     * @param typeVariable  whether the result type is a type variable
     */
    record MethodShape(String name, List<Boolean> typeVariables, boolean typeVariable) {}

    /**
     * The parameters of a method or constructor, each as its erased canonical type, `java.lang.String[]`;
     * an array of a type variable is written with the name of the variable, `T[]`.
     *
     * @param varargs     whether the last parameter takes any number of arguments
     * @param returnsVoid whether it is a method without a result
     */
    record Signature(List<String> parameters, boolean varargs, boolean returnsVoid) {
        public Signature(List<String> parameters, boolean varargs) {
            this(parameters, varargs, false);
        }
    }

    /** The class, or null when it cannot be read. */
    ClassShape shape(String qualifiedName);

    /**
     * The instance methods of the interface and of the interfaces it extends; empty when it is not an
     * interface that can be read. A method that has a type variable where an `Int` is written gets the
     * box `Integer` in Java, not `int`.
     */
    List<MethodShape> methods(String qualifiedName);

    /**
     * The public methods of that name, static or not and also inherited, or the public constructors for
     * `<init>`; empty when the class cannot be read. The translator passes a list where Java takes an array,
     * and writes a lambda whose body is a `?.` call of a method without a result as a statement.
     */
    default List<Signature> signatures(String qualifiedName, String name) {
        return List.of();
    }

    /**
     * The result type of the public methods of that name that take that many arguments, as an erased canonical
     * type such as `int` or `java.lang.String`; null when there is none, when they differ, when one is a type
     * variable, or when a method with varargs might take the arguments. The translator types a local set from
     * such a call, as `val ch = reader.read()`.
     */
    default String result(String qualifiedName, String name, int arity) {
        return null;
    }
}
