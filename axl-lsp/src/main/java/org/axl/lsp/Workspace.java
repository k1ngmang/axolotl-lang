package org.axl.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.GeneratedUnit;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * All state of the server. Every request runs on one thread, so the state needs no locking.
 */
final class Workspace {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);

    /** The errors the compiler found in a document, and the version of its text they are about. */
    private record Compiled(Document version, List<AxlDiagnostic> errors) {}

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "axl-lsp");
        thread.setDaemon(true);
        return thread;
    });
    private LanguageClient client;
    private ProjectModel project = ProjectModel.discover(List.of());
    private final Map<String, Document> open = new HashMap<>();
    // The last version that parsed, used while the user is typing an incomplete line.
    private final Map<String, Document> lastParsed = new HashMap<>();
    // By module, then by document URI; replaced as a whole by each compilation of a module.
    private final Map<Module, Map<String, Compiled>> compileErrors = new HashMap<>();
    private final Map<Module, JavaModel> javaModels = new HashMap<>();
    private ModuleCompiler compiler;

    <T> CompletableFuture<T> submit(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    void connect(LanguageClient client) {
        this.client = client;
    }

    void open(List<Path> roots) throws IOException {
        project = ProjectModel.discover(roots);
        compiler = new ModuleCompiler();
    }

    void close() {
        executor.shutdownNow();
    }

    void update(String uri, String text) {
        Document document = new Document(uri, text, project);
        open.put(uri, document);
        if (document.ast != null) {
            lastParsed.put(uri, document);
        }
        javaModels.remove(document.module);
        publish(document);
    }

    void closeDocument(String uri) {
        open.remove(uri);
        lastParsed.remove(uri);
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    void saved(String uri) throws IOException {
        Document document = open.get(uri);
        if (document == null || document.translation.hasErrors()) {
            return;
        }
        List<Document> moduleDocuments = documentsOf(document.module);
        Map<String, List<AxlDiagnostic>> errors = compiler.compile(document.module, moduleDocuments);
        Map<String, Compiled> compiled = new HashMap<>();
        for (Document d : moduleDocuments) {
            compiled.put(d.uri, new Compiled(d, errors.getOrDefault(d.uri, List.of())));
        }
        compileErrors.put(document.module, compiled);
        for (Document d : moduleDocuments) {
            if (open.containsKey(d.uri)) {
                publish(open.get(d.uri));
            }
        }
    }

    Document document(String uri) {
        return open.get(uri);
    }

    /** The current document if it parses, otherwise its last version that did. */
    Document parsed(String uri) {
        Document current = open.get(uri);
        return current != null && current.ast != null ? current : lastParsed.get(uri);
    }

    JavaModel javaModel(Module module) {
        return javaModels.computeIfAbsent(module, m -> JavaModel.build(m, documentsOf(m).stream()
                .map(d -> parsed(d.uri) != null ? parsed(d.uri) : d)
                .map(d -> d.translation.unit()).filter(u -> u != null).toList()));
    }

    /** Every .axl file of a module, open ones with their unsaved text. */
    List<Document> documentsOf(Module module) {
        List<Document> result = new ArrayList<>();
        boolean loose = !project.modules().contains(module);
        try {
            for (Path root : module.axlRoots()) {
                for (Path file : loose ? looseFiles(root) : axlFiles(root)) {
                    Document doc = findOpen(file);
                    result.add(doc != null ? doc
                            : new Document(file.toUri().toString(), Files.readString(file, StandardCharsets.UTF_8), project));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        open.values().stream().filter(d -> d.module.equals(module) && result.stream().noneMatch(r -> r.path.equals(d.path)))
                .forEach(result::add);
        return result;
    }

    private static List<Path> axlFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".axl")).sorted().toList();
        }
    }

    /**
     * The .axl files under the source root of a file outside every module, only those whose package
     * matches their directory: the other directories may hold anything, such as old copies.
     */
    private static List<Path> looseFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                boolean packageName = dir.equals(root) || SourceVersion.isIdentifier(dir.getFileName().toString());
                return packageName ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.toString().endsWith(".axl") && packageDirectory(file).equals(root.relativize(file.getParent()))) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(null);
        return result;
    }

    /** The directory of a file's package relative to its source root, from its package declaration. */
    private static Path packageDirectory(Path file) throws IOException {
        Matcher matcher = PACKAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
        return matcher.find() ? Path.of("", matcher.group(1).split("\\.")) : Path.of("");
    }

    private Document findOpen(Path file) {
        return open.values().stream().filter(d -> d.path.equals(file)).findFirst().orElse(null);
    }

    /** The source of a class: an .axl file in any module, or a .java file in a Java source root. */
    Path sourceOf(String qualifiedName) {
        String relative = qualifiedName.replace('.', '/');
        for (Module module : project.modules()) {
            for (Path root : module.axlRoots()) {
                Path axl = root.resolve(relative + ".axl");
                if (Files.exists(axl)) {
                    return axl;
                }
            }
            for (Path root : module.javaRoots()) {
                Path java = root.resolve(relative + ".java");
                if (Files.exists(java)) {
                    return java;
                }
            }
        }
        return null;
    }

    /** An .axl document for a path: the open one, or read from disk. */
    Document load(Path file) {
        Document doc = findOpen(file);
        if (doc != null) {
            return doc;
        }
        try {
            return new Document(file.toUri().toString(), Files.readString(file, StandardCharsets.UTF_8), project);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    GeneratedUnit generated(String uri) {
        Document doc = parsed(uri);
        return doc == null ? null : doc.translation.unit();
    }

    private void publish(Document document) {
        if (client == null) {
            return;
        }
        List<AxlDiagnostic> all = new ArrayList<>(document.diagnostics());
        Compiled compiled = compileErrors.getOrDefault(document.module, Map.of()).get(document.uri);
        if (!document.translation.hasErrors() && compiled != null) {
            all.addAll(movedTo(document, compiled));
        }
        List<Diagnostic> diagnostics = all.stream().map(d -> toLsp(document, d)).toList();
        client.publishDiagnostics(new PublishDiagnosticsParams(document.uri, diagnostics));
    }

    /** Errors of the compiled version on the lines that are unchanged in the current text, moved to them. */
    private static List<AxlDiagnostic> movedTo(Document current, Compiled compiled) {
        List<AxlDiagnostic> result = new ArrayList<>();
        for (AxlDiagnostic d : compiled.errors()) {
            int line = compiled.version().sameLineIn(current, d.line() - 1);
            if (line >= 0) {
                result.add(new AxlDiagnostic(d.file(), line + 1, d.column(), d.severity(), d.code(), d.message()));
            }
        }
        return result;
    }

    /** The range covers the token at the reported column, or the rest of the line. */
    static Diagnostic toLsp(Document document, AxlDiagnostic d) {
        int line = Math.max(0, d.line() - 1);
        int column = Math.max(0, d.column() - 1);
        int start = document.utf16Column(line, column);
        var token = document.tokenAt(line, start);
        int end = token != null && token.getCharPositionInLine() == column
                ? document.utf16Column(line, column + token.getStopIndex() - token.getStartIndex() + 1)
                : line < document.lines.size() ? Math.max(start + 1, document.lines.get(line).length()) : start + 1;
        Diagnostic diagnostic = new Diagnostic(new Range(new Position(line, start), new Position(line, end)), d.message(),
                d.isError() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning, "axl");
        diagnostic.setCode(Either.forLeft(d.code().id()));
        return diagnostic;
    }
}
