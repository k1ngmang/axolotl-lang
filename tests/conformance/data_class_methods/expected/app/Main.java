package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public interface Priced {
        long price();
        String label(String prefix);
    }
    public record Money(long cents) implements Comparable<Money> {
        public int compareTo(Money other) {
            Nulls.param(other, "other");
            return Long.compare(cents, other.cents());
        }
        public Money plus(Money other) {
            Nulls.param(other, "other");
            return new Money(cents + other.cents());
        }
        public String show() {
            return cents / 100 + "." + String.format("%02d", cents % 100);
        }
    }
    public record Item(String name, Money money) implements Priced {
        public Item {
            Nulls.param(name, "name");
            Nulls.param(money, "money");
        }
        @Override public long price() {
            return money.cents();
        }
        @Override public String label(String prefix) {
            Nulls.param(prefix, "prefix");
            return prefix + name + " " + money.show();
        }
    }
    public static void main(String[] args) {
        final var prices = new ArrayList<Money>();
        prices.add(new Money(250));
        prices.add(new Money(99));
        prices.add(new Money(1000));
        Collections.sort(prices);
        System.out.println(prices.stream().map(Nulls.nonNull(it -> it.show())).toList());
        System.out.println(new Money(250).plus(new Money(99)).show());
        final var item = new Item("tea", new Money(350));
        final Priced priced = item;
        System.out.println(priced.label("* ") + " " + priced.price());
        System.out.println(item.equals(new Item("tea", new Money(350))));
    }
}
