package org.axl.lsp;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import com.google.gson.JsonElement;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.Pos;
import org.axl.compiler.parse.AxolotlLexer;
import org.axl.javac.Translation;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * All state of the server. Every request runs on one thread, so the state needs no locking.
 */
final class Workspace {

    /** The errors the compiler found in a document, and the version of its text they are about. */
    private record Compiled(Document version, List<AxlDiagnostic> errors) {}

    /** A Java model and the generated Java it was built from. */
    private record CachedModel(List<String> sources, JavaModel model) {}

    // javac and the scanners of its trees recurse once per nesting level of an expression, like the translator.
    private static final long STACK_SIZE = 512L * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> thread(r, "axl-lsp"));
    private final ExecutorService compiles = Executors.newSingleThreadExecutor(r -> thread(r, "axl-lsp-compile"));
    // The number of compilations asked for by module, read by the compiling thread to skip outdated ones.
    private final Map<Module, Integer> compileRequests = new ConcurrentHashMap<>();
    // The newest text of each document with changes waiting for the request thread.
    private final Map<String, String> pendingText = new ConcurrentHashMap<>();
    private LanguageClient client;
    private List<Path> roots = List.of();
    private JsonElement configuredModules;
    private ProjectModel project = ProjectModel.discover(roots);
    private final Map<String, Document> open = new HashMap<>();
    // The last version that parsed, used while the user is typing an incomplete line.
    private final Map<String, Document> lastParsed = new HashMap<>();
    // By module, then by document URI; replaced as a whole by each compilation of a module.
    private final Map<Module, Map<String, Compiled>> compileErrors = new HashMap<>();
    // Files that are not open with diagnostics published, by URI, with their module.
    private final Map<String, Module> publishedClosed = new HashMap<>();
    private final Map<Module, CachedModel> javaModels = new HashMap<>();
    // Files that are not open, by path, translated once for each text they have on disk.
    private final Map<Path, Document> closedDocuments = new HashMap<>();
    private final Map<Module, Path> drafts = new HashMap<>();
    // The Java file in the drafts directory of each .axl file, deleted when its class or package changes.
    private final Map<Path, Path> draftFiles = new HashMap<>();
    private ModuleCompiler compiler;
    // Off for a client that shows the translator's diagnostics itself, as the IntelliJ plugin does.
    private boolean translatorDiagnostics = true;

    private static Thread thread(Runnable work, String name) {
        Thread thread = new Thread(null, work, name, STACK_SIZE);
        thread.setDaemon(true);
        return thread;
    }

    <T> CompletableFuture<T> submit(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    /** Work for a notification, which has no response to carry a failure, so failures go to the log. */
    void submitLogged(String what, Callable<?> work) {
        submit(work).whenComplete((result, e) -> {
            if (e != null) {
                logFailure(what, e);
            }
        });
    }

    void connect(LanguageClient client) {
        this.client = client;
    }

    void open(List<Path> roots) throws IOException {
        open(roots, null);
    }

    /** modules: the modules the client describes, see {@link ProjectModel#of}; null to find them in the roots. */
    void open(List<Path> roots, JsonElement modules) throws IOException {
        if (compiler != null) {
            compiler.close();
        }
        compiler = new ModuleCompiler();
        this.roots = roots;
        this.configuredModules = modules;
        discover();
    }

    /** Finds the modules, and forgets everything derived from the ones found before. */
    private void discover() {
        project = configuredModules != null ? ProjectModel.of(configuredModules) : ProjectModel.discover(roots);
        deleteDrafts();
        drafts.clear();
        draftFiles.clear();
        closedDocuments.clear();
        javaModels.clear();
        compileErrors.clear();
        if (client != null) {
            project.problems().forEach(p -> client.showMessage(new MessageParams(MessageType.Warning, p)));
        }
    }

    void publishTranslatorDiagnostics(boolean enabled) {
        translatorDiagnostics = enabled;
    }

    /**
     * Not interrupted: javac interrupted while reading ct.sym closes it for every later compilation
     * in the JVM. The threads are daemons and end with it.
     */
    void close() {
        compiles.shutdown();
        executor.shutdown();
        deleteTemporaryFiles();
    }

    private void deleteTemporaryFiles() {
        if (compiler != null) {
            compiler.close();
        }
        deleteDrafts();
    }

    private void deleteDrafts() {
        for (Path dir : drafts.values()) {
            try {
                ModuleCompiler.delete(dir);
            } catch (IOException | UncheckedIOException e) {
                // A temporary directory left behind does no harm.
            }
        }
    }

    /**
     * A change from the protocol thread. Of several changes of a document waiting for the request
     * thread, only the newest text is translated, so that typing does not queue outdated work.
     */
    void changeLater(String uri, String text) {
        pendingText.put(uri, text);
        submitLogged("Changing " + uri, () -> {
            String newest = pendingText.remove(uri);
            if (newest != null) {
                update(uri, newest);
            }
            return null;
        });
    }

    void update(String uri, String text) {
        Document document = new Document(uri, text, project, this::draftsOf);
        open.put(uri, document);
        writeDraft(document);
        if (document.ast != null) {
            lastParsed.put(uri, document);
        }
        publish(document);
    }

    /** The draft goes back to the text on disk, which may differ when the document was not saved. */
    void closeDocument(String uri) {
        Document closed = open.remove(uri);
        lastParsed.remove(uri);
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
        if (closed != null) {
            refreshDraft(closed.path);
        }
    }

    /**
     * Files changed outside the editor, such as by a checkout. Open documents keep their text, but
     * are translated again against the new drafts and Java sources.
     */
    void filesChanged(List<Path> files) {
        boolean modulesChanged = files.stream().anyMatch(Workspace::describesModules);
        if (modulesChanged) {
            discover();
        }
        for (Path file : files) {
            if (!modulesChanged && file.toString().endsWith(".axl") && findOpen(file) == null) {
                refreshDraft(file);
            }
        }
        javaModels.clear();
        for (Document document : List.copyOf(open.values())) {
            update(document.uri, document.text);
            if (modulesChanged) {
                compileLater(document.uri, true);
            }
        }
    }

    /** A module description, or a source set directory of the layout that guesses modules. */
    private static boolean describesModules(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        Path src = file.getParent() == null ? null : file.getParent().getParent();
        return name.equals("axl.json") || name.equals("axl") && src != null && src.getFileName() != null
                && src.getFileName().toString().equals("src");
    }

    /** Writes the draft of a closed file from disk, or deletes it when the file is gone. */
    private void refreshDraft(Path file) {
        if (Files.exists(file)) {
            writeDraft(load(file));
            return;
        }
        Path old = draftFiles.remove(file);
        if (old != null && !draftFiles.containsValue(old)) {
            deleteDraft(old);
        }
    }

    /** Compiles the modules that see a document and publishes the errors. */
    void saved(String uri) throws IOException {
        Document document = open.get(uri);
        if (document == null || document.translation.hasErrors()) {
            return;
        }
        for (Module module : modulesSeeing(document)) {
            List<Document> moduleDocuments = documentsOf(module);
            compiled(module, moduleDocuments, compiler.compile(module, moduleDocuments, standIns(moduleDocuments)));
        }
    }

    /** The module of a document, then the other modules whose sources include it, as tests include the main sources. */
    private List<Module> modulesSeeing(Document document) {
        List<Module> result = new ArrayList<>(List.of(document.module));
        project.modules().stream().filter(m -> !m.equals(document.module) && m.axlRootOf(document.path) != null)
                .forEach(result::add);
        return result;
    }

    /**
     * The Java of the documents that do not translate: of the text without the lines with errors, or
     * of the last version that parsed. Compiled in their place, it keeps the declarations their users
     * see, as the drafts do while typing.
     */
    private Map<String, GeneratedUnit> standIns(List<Document> documents) {
        Map<String, GeneratedUnit> result = new HashMap<>();
        for (Document document : documents) {
            Document parsed = document.ast != null ? document : lastParsed.get(document.uri);
            GeneratedUnit unit = document.translation.unit() != null || parsed == null ? null : parsed.javaUnit();
            if (unit != null) {
                result.put(document.uri, unit);
            }
        }
        return result;
    }

    /**
     * Like {@link #saved}, but compiles on another thread, so that requests are answered meanwhile.
     * Of several compilations of a module waiting, only the last runs. firstOnly: only the module of
     * the document, and skipped when it was compiled already, as for a document being opened.
     */
    void compileLater(String uri, boolean firstOnly) {
        Document document = open.get(uri);
        if (document == null || document.translation.hasErrors()) {
            return;
        }
        if (!firstOnly) {
            modulesSeeing(document).forEach(this::compileLater);
        } else if (!compileRequests.containsKey(document.module)) {
            compileLater(document.module);
        }
    }

    private void compileLater(Module module) {
        List<Document> moduleDocuments = documentsOf(module);
        Map<String, GeneratedUnit> standIns = standIns(moduleDocuments);
        int request = compileRequests.merge(module, 1, Integer::sum);
        compiles.execute(() -> {
            if (compileRequests.get(module) != request) {
                return;
            }
            try {
                Map<String, List<AxlDiagnostic>> errors = compiler.compile(module, moduleDocuments, standIns);
                submit(() -> {
                    compiled(module, moduleDocuments, errors);
                    return null;
                });
            } catch (IOException | RuntimeException e) {
                logFailure("Compiling " + module.root(), e);
            }
        });
    }

    /** Sends a failure to the client's log, since a notification has no response to carry it. */
    void logFailure(String what, Throwable e) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        System.err.println(what + " failed: " + trace);
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Error, what + " failed: " + trace));
        }
    }

    private void compiled(Module module, List<Document> moduleDocuments, Map<String, List<AxlDiagnostic>> errors) {
        Map<String, Compiled> compiled = new HashMap<>();
        for (Document d : moduleDocuments) {
            compiled.put(d.uri, new Compiled(d, errors.getOrDefault(d.uri, List.of())));
        }
        compileErrors.put(module, compiled);
        for (Document d : moduleDocuments) {
            if (open.containsKey(d.uri)) {
                publish(open.get(d.uri));
            } else if (d.module.equals(module)) {
                publishClosed(d.uri, module, diagnostics(d));
            }
        }
        Set<String> files = new HashSet<>(compiled.keySet());
        for (var entry : errors.entrySet()) {
            if (!compiled.containsKey(entry.getKey())) {
                files.add(entry.getKey());
                publishClosed(entry.getKey(), module, javaDiagnostics(entry.getKey(), entry.getValue()));
            }
        }
        clearDeleted(module, files);
    }

    /** Clears the diagnostics of the files of a module that are gone. */
    private void clearDeleted(Module module, Set<String> files) {
        List<String> gone = publishedClosed.entrySet().stream()
                .filter(e -> e.getValue().equals(module) && !files.contains(e.getKey())).map(Map.Entry::getKey).toList();
        for (String uri : gone) {
            publishedClosed.remove(uri);
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    /**
     * The errors of a file that is not open or of a Java source, as a build shows them; an empty list
     * only to clear earlier ones.
     */
    private void publishClosed(String uri, Module module, List<Diagnostic> diagnostics) {
        if (client == null) {
            return;
        }
        if (!diagnostics.isEmpty() || publishedClosed.remove(uri) != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
        if (!diagnostics.isEmpty()) {
            publishedClosed.put(uri, module);
        }
    }

    /** javac's errors in a Java source, from the reported column to the end of the line. */
    private static List<Diagnostic> javaDiagnostics(String uri, List<AxlDiagnostic> errors) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(URI.create(uri)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            lines = List.of();
        }
        List<Diagnostic> result = new ArrayList<>();
        for (AxlDiagnostic d : errors) {
            int line = Math.max(0, d.line() - 1);
            int start = Math.max(0, d.column() - 1);
            int end = line < lines.size() ? Math.max(start + 1, lines.get(line).length()) : start + 1;
            result.add(diagnostic(new Range(new Position(line, start), new Position(line, end)), d));
        }
        return result;
    }


    Document document(String uri) {
        return open.get(uri);
    }

    /** The current document if it parses, otherwise its last version that did. */
    Document parsed(String uri) {
        Document current = open.get(uri);
        return current != null && current.ast != null ? current : lastParsed.get(uri);
    }

    /** Built again only when the generated Java changed, which editing a comment or an unfinished line does not. */
    JavaModel javaModel(Module module) {
        List<GeneratedUnit> units = documentsOf(module).stream().map(d -> parsed(d.uri) != null ? parsed(d.uri) : d)
                .map(Document::javaUnit).filter(u -> u != null).toList();
        List<String> sources = units.stream().map(GeneratedUnit::javaSource).toList();
        CachedModel cached = javaModels.get(module);
        if (cached == null || !cached.sources().equals(sources)) {
            cached = new CachedModel(sources, JavaModel.build(module, units));
            javaModels.put(module, cached);
        }
        return cached.model();
    }

    /** The arguments of a call for choosing an overload, with the types of names that are known. */
    List<String> argumentTypes(Document document, Token name) {
        return document.argumentTypes(name, argument -> typeName(document, Document.start(argument), argument.getText()));
    }

    /** The type of a name visible at a position, as Axolotl writes it without type arguments; null if unknown. */
    String typeName(Document document, Pos pos, String name) {
        Symbols.Symbol symbol = Symbols.lookup(document.ast, pos, name);
        if (symbol == null || symbol.type() != null) {
            return symbol == null ? null : symbol.type().name();
        }
        return JavaModel.axlName(javaModel(document.module).typeOf(document.ast, symbol));
    }

    /** Every .axl file of a module, open ones with their unsaved text. */
    List<Document> documentsOf(Module module) {
        List<Document> result = new ArrayList<>();
        try {
            for (Path root : module.axlRoots()) {
                for (Path file : filesOf(module, root)) {
                    Document doc = findOpen(file);
                    result.add(doc != null ? doc : closedDocument(file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        open.values().stream().filter(d -> d.module.equals(module) && result.stream().noneMatch(r -> r.path.equals(d.path)))
                .forEach(result::add);
        return result;
    }

    /**
     * The Java of every .axl file of a module, which javac reads when the type of a value depends on
     * another file. Written by translating the module once, then kept up to date by each edit.
     */
    private Path draftsOf(Module module) {
        Path dir = drafts.get(module);
        if (dir != null) {
            return dir;
        }
        try {
            dir = Files.createTempDirectory("axl-lsp-drafts");
            List<Translation.Input> inputs = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            for (Path root : module.axlRoots()) {
                for (Path file : filesOf(module, root)) {
                    Document doc = findOpen(file);
                    String text = doc != null ? doc.text : Files.readString(file, StandardCharsets.UTF_8);
                    String sourcePath = root.relativize(file).toString().replace('\\', '/');
                    inputs.add(new Translation.Input(sourcePath, file.toString(), text));
                    files.add(file);
                }
            }
            List<Translator.Result> results = Translation.run(inputs, new Options(false), module.classpath(),
                    module.sourcepath(), dir, module.strictNull());
            Options options = Document.options(module, dir);
            for (int i = 0; i < results.size(); i++) {
                Translator.Result result = results.get(i);
                Translation.Input input = inputs.get(i);
                GeneratedUnit unit = result.unit() != null || isDuplicate(result) ? result.unit()
                        : Document.partialUnit(input.sourcePath(), input.displayPath(), input.text(), options, result.diagnostics());
                if (unit != null) {
                    draftFiles.put(files.get(i), unit.writeTo(dir));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        drafts.put(module, dir);
        return dir;
    }

    private static boolean isDuplicate(Translator.Result result) {
        return result.diagnostics().stream().anyMatch(d -> d.code() == ErrorCode.DUPLICATE_CLASS);
    }

    private void writeDraft(Document document) {
        GeneratedUnit unit = document.javaUnit();
        if (unit == null) {
            return;
        }
        try {
            Path written = unit.writeTo(draftsOf(document.module));
            // The types of values inferred from the drafts may have changed.
            closedDocuments.values().removeIf(Document::infersValueTypes);
            Path old = draftFiles.put(document.path, written);
            if (old != null && !draftFiles.containsValue(old)) {
                deleteDraft(old);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteDraft(Path javaFile) {
        try {
            Files.deleteIfExists(javaFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> filesOf(Module module, Path root) throws IOException {
        return project.modules().contains(module) ? axlFiles(root) : looseFiles(root);
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
        String name = packageName(Files.readString(file, StandardCharsets.UTF_8));
        return name.isEmpty() ? Path.of("") : Path.of("", name.split("\\."));
    }

    /** The package a file declares, read with the Axolotl lexer, which skips comments; empty if none. */
    static String packageName(String text) {
        AxolotlLexer lexer = new AxolotlLexer(CharStreams.fromString(text));
        if (nextToken(lexer).getType() != AxolotlLexer.PACKAGE) {
            return "";
        }
        StringBuilder name = new StringBuilder();
        for (Token token = nextToken(lexer); token.getType() == AxolotlLexer.IDENTIFIER; token = nextToken(lexer)) {
            name.append(token.getText());
            if (nextToken(lexer).getType() != AxolotlLexer.DOT) {
                break;
            }
            name.append('.');
        }
        return name.toString();
    }

    private static Token nextToken(AxolotlLexer lexer) {
        Token token = lexer.nextToken();
        while (token.getChannel() != Token.DEFAULT_CHANNEL) {
            token = lexer.nextToken();
        }
        return token;
    }

    private Document findOpen(Path file) {
        return open.values().stream().filter(d -> d.path.equals(file)).findFirst().orElse(null);
    }

    /**
     * The source of a class: an .axl file, which may exist only in the editor, or a .java file in a
     * Java source root, of the module of the file asking first, which may be a loose module, then of
     * any module.
     */
    Path sourceOf(Module from, String qualifiedName) {
        String relative = qualifiedName.replace('.', '/');
        List<Module> modules = new ArrayList<>(List.of(from));
        modules.addAll(project.modules());
        for (Module module : modules) {
            for (Path root : module.axlRoots()) {
                Path axl = root.resolve(relative + ".axl");
                if (findOpen(axl) != null || Files.exists(axl)) {
                    return axl;
                }
            }
            for (Path root : module.sourcepath()) {
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
            return closedDocument(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Document closedDocument(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Document cached = closedDocuments.get(file);
        if (cached == null || !cached.text.equals(text)) {
            cached = new Document(file.toUri().toString(), text, project, this::draftsOf);
            closedDocuments.put(file, cached);
        }
        return cached;
    }

    GeneratedUnit generated(String uri) {
        Document doc = parsed(uri);
        return doc == null ? null : doc.translation.unit();
    }

    private void publish(Document document) {
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(document.uri, diagnostics(document)));
        }
    }

    private List<Diagnostic> diagnostics(Document document) {
        List<AxlDiagnostic> all = new ArrayList<>(translatorDiagnostics ? document.diagnostics() : List.of());
        Compiled compiled = compileErrors.getOrDefault(document.module, Map.of()).get(document.uri);
        if (!document.translation.hasErrors() && compiled != null) {
            all.addAll(movedTo(document, compiled));
        }
        // The translator may report the same problem twice, such as for both branches of one arm.
        return all.stream().map(d -> toLsp(document, d)).distinct().toList();
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
        return diagnostic(new Range(new Position(line, start), new Position(line, end)), d);
    }

    private static Diagnostic diagnostic(Range range, AxlDiagnostic d) {
        Diagnostic diagnostic = new Diagnostic(range, d.message(), d.isError() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning, "axl");
        diagnostic.setCode(Either.forLeft(d.code().id()));
        return diagnostic;
    }
}
