package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Comparator;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public record Person(String name, int age) {}
    public static final Comparator<Person> byAge = Comparator.comparingInt(p -> p.age());
    public static void main(String[] args) {
        final var people = List.of(new Person("ann", 41), new Person("bob", 29), new Person("cid", 35));
        System.out.println(people.stream().sorted(byAge).map(Nulls.nonNull(it -> it.name())).toList());
        System.out.println(people.stream().sorted(Comparator.comparing((Person p) -> p.name()).reversed()).map(Nulls.nonNull(it -> it.name())).toList());
        System.out.println(people.stream().sorted((a, b) -> b.age() - a.age()).map(Nulls.nonNull(it -> it.age())).toList());
    }
}
