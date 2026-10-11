package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Opt {
    public final @Nullable String opt;
    public final @Nullable String longOpt;
    public final boolean hasArg;
    private final @Nullable String description;
    public Opt(@Nullable String opt, @Nullable String longOpt, boolean hasArg, @Nullable String description) {
        this.opt = opt;
        this.longOpt = longOpt;
        this.hasArg = hasArg;
        this.description = description;
    }
    public Opt(@Nullable String opt, @Nullable String description) {
        this(opt, null, false, description);
    }
    public Opt(@Nullable String opt, boolean hasArg, @Nullable String description) {
        this(opt, null, hasArg, description);
    }
    @Deprecated public Opt(
            @Nullable String opt) {
        this(opt, null, false, "deprecated: " + opt);
    }
    private Opt() {
        this("?", null, false, null);
    }
    public String describe() {
        return opt + "/" + longOpt + "/" + hasArg + "/" + Nulls.<String>or(description, () -> "-");
    }
    public Opt blank() {
        return new Opt();
    }
}
