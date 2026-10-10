package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public interface Named {
        String name();
    }
    public sealed interface Animal extends Named {
        int legs();
    }
    public static final class Dog implements Animal {
        public final String name;
        public Dog(String name) {
            Nulls.param(name, "name");
            this.name = name;
        }
        @Override public int legs() {
            return 4;
        }
        @Override public String name() { return name; }
    }
    public static final class Bird implements Animal {
        public final String name;
        public int legs;
        public Bird(String name, int legs) {
            Nulls.param(name, "name");
            this.name = name;
            this.legs = legs;
        }
        @Override public int legs() { return legs; }
        @Override public String name() { return name; }
    }
    public static String describe(Animal animal) {
        Nulls.param(animal, "animal");
        return animal.name() + " has " + animal.legs() + " legs";
    }
    public static void main(String[] args) {
        System.out.println(describe(new Dog("rex")));
        System.out.println(describe(new Bird("tweety", 2)));
    }
}
