package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Iterator;
import java.util.List;

@NullMarked public final class Bag implements Iterable<Bag.Member> {
    private final List<String> names;
    public Bag(List<String> names) {
        Nulls.param(names, "names");
        this.names = names;
    }
    public Iterator<Member> iterator() {
        return (names.stream().map(Nulls.nonNull(it -> new Member(it))).toList()).iterator();
    }
    public static final class Member {
        public final String name;
        public Member(String name) {
            Nulls.param(name, "name");
            this.name = name;
        }
    }
}
