package lib;
public class P {
    public String name;
    public P(String n) { name = n; }
    public String getName() { return name; }
    public static P empty() { P p = new P("x"); p.name = null; return p; }
}