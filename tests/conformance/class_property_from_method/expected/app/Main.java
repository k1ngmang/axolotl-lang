package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static String base() {
        return "top level";
    }
    public static final class Account {
        public final String owner;
        public final int cents;
        public final int balance;
        public final int doubled;
        public final String label;
        public final List<String> names;
        public final String first;
        public Account(String owner, int cents) {
            this.owner = owner;
            this.cents = cents;
            this.balance = compute();
            this.doubled = scaled(2);
            this.label = base();
            this.names = parts();
            this.first = names.getFirst();
        }
        public int compute() {
            return cents * 2;
        }
        public int scaled(int n) {
            return n * cents;
        }
        public String base() {
            return "method of " + owner;
        }
        public List<String> parts() {
            return List.of(owner, "x");
        }
    }
    public static void main(String[] args) {
        final var account = new Account("ann", 5);
        System.out.println(account.balance);
        System.out.println(account.doubled);
        System.out.println(account.label);
        System.out.println(account.names);
        System.out.println(account.first);
    }
}
