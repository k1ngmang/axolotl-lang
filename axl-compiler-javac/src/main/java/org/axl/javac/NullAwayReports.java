package org.axl.javac;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticType;
import com.sun.tools.javac.util.Log;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Remembers where NullAway reported errors, so that NullChecker does not report the same problem again. */
final class NullAwayReports extends Log.DiagnosticHandler {

    private record Reported(URI file, long start) {}

    private final List<Reported> reported = new ArrayList<>();

    NullAwayReports(Context context) {
        install(Log.instance(context));
    }

    @Override
    public void report(JCDiagnostic diagnostic) {
        if (diagnostic.getType() == DiagnosticType.ERROR && diagnostic.getSource() != null
                && diagnostic.getMessage(Locale.ROOT).startsWith("[NullAway]")) {
            long start = diagnostic.getStartPosition() < 0 ? diagnostic.getPosition() : diagnostic.getStartPosition();
            reported.add(new Reported(diagnostic.getSource().toUri(), start));
        }
        prev.report(diagnostic);
    }

    /** Error Prone gives its errors no end position. */
    List<Long> starts(URI file) {
        return reported.stream().filter(r -> r.file().equals(file)).map(Reported::start).toList();
    }
}
