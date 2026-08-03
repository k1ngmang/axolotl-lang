package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public record Token(Character ch) {}
    public static boolean same(Character a, Character b) {
        return Nulls.same(a, b);
    }
    public static boolean closes(Token token, Character ch) {
        return Nulls.same(token.ch(), ch);
    }
    public static boolean differ(Token a, Token b) {
        return !Nulls.same(a.ch(), b.ch());
    }
    public static void main(String[] args) {
        System.out.println(same('я', 'я'));
        System.out.println(closes(new Token('я'), 'я'));
        System.out.println(differ(new Token('я'), new Token('ю')));
        System.out.println(differ(new Token('я'), new Token('я')));
        System.out.println(new Token('я').ch() == 'я');
    }
}
