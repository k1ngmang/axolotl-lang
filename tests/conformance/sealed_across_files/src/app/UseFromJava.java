package app;

import app.DeleteDelta;
import app.InsertDelta;
import java.util.List;

public class UseFromJava {
    public static int sizes() {
        return Delta.size(new InsertDelta(List.of("a", "b"))) + Delta.size(new DeleteDelta(List.of("c")));
    }
}
