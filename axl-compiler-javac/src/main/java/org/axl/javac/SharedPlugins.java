package org.axl.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads the javac plugins of a compilation from a class loader kept for its processor path, when the
 * javac option {@code -XDaxl.sharedPlugins} asks for it. javac and Gradle load plugins with a new class
 * loader for each compilation, so a JVM that compiles again and again, such as Gradle's compiler daemon,
 * loads Error Prone again every time and the JIT compiles it again, which takes longer than its checks.
 * The loader is kept while the jars of the path keep their sizes and times; a path with a directory gets
 * no kept loader. Without the option, or with the plugins on the classpath of the JVM as in `axl`, each
 * plugin comes from the loader javac found it in.
 */
public final class SharedPlugins {

    /**
     * The javac option that turns sharing on. Only a JVM that does nothing but compile should get it: the
     * loaders are kept in its system properties, and a program that lists them expects only strings.
     */
    public static final String OPTION = "axl.sharedPlugins";
    /** Every class loader of the JVM sees the system properties; a static field would be one per loader of this class. */
    static final String PROPERTY = "org.axl.javac.sharedPluginLoaders";
    private static final int KEPT = 4;
    private static final String ERROR_PRONE = "com.google.errorprone.ErrorProneJavacPlugin";
    private static final Field PROCESSOR_LOADER = processorLoader();

    private SharedPlugins() {}

    /**
     * Error Prone under its own name. javac starts the first plugin of a name on the processor path, where
     * this jar comes before Error Prone's; with Error Prone first, javac starts that one and skips this.
     */
    public static final class ErrorProne implements Plugin {

        @Override
        public String getName() {
            return "ErrorProne";
        }

        @Override
        public void init(JavacTask task, String... args) {
            ClassLoader own = getClass().getClassLoader();
            ClassLoader loader = loader(task, own);
            Plugin plugin;
            try {
                plugin = load(ERROR_PRONE, loader);
            } catch (ClassNotFoundException e) {
                // What javac reports for a plugin that is not on the processor path.
                Log.instance(((BasicJavacTask) task).getContext())
                        .error(new JCDiagnostic.Error("compiler", "plugin.not.found", getName()));
                return;
            }
            if (loader != own) {
                // Error Prone loads its own plugins, such as NullAway, from the processor class loader.
                task.addTaskListener(new UseLoader((BasicJavacTask) task, loader));
            }
            plugin.init(task, args);
        }
    }

    /** AxlDiagnostics, the plugin of {@link DiagnosticsPlugin}. */
    public static final class Diagnostics implements Plugin {

        @Override
        public String getName() {
            return DiagnosticsPlugin.NAME;
        }

        @Override
        public void init(JavacTask task, String... args) {
            try {
                load(DiagnosticsPlugin.class.getName(), loader(task, getClass().getClassLoader())).init(task, args);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Plugin load(String className, ClassLoader loader) throws ClassNotFoundException {
        try {
            return (Plugin) Class.forName(className, true, loader).getConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A kept loader for the processor path when javac loaded the plugin from a loader of its own for this
     * compilation, else the plugin's own loader.
     */
    private static ClassLoader loader(JavacTask task, ClassLoader own) {
        // Windows does not delete a file that is open, and a kept loader keeps its jars open.
        if (!(task instanceof BasicJavacTask basic) || !(own instanceof URLClassLoader urls) || PROCESSOR_LOADER == null
                || File.separatorChar == '\\' || !Options.instance(basic.getContext()).isSet(OPTION)
                || JavacProcessingEnvironment.instance(basic.getContext()).getProcessorClassLoader() != own) {
            return own;
        }
        List<Object> key = key(urls.getURLs());
        if (key == null) {
            return own;
        }
        Properties properties = System.getProperties();
        synchronized (properties) {
            @SuppressWarnings("unchecked")
            Map<Object, ClassLoader> loaders = (Map<Object, ClassLoader>) properties.get(PROPERTY);
            if (loaders == null) {
                loaders = new LinkedHashMap<>();
                properties.put(PROPERTY, loaders);
            }
            ClassLoader loader = loaders.remove(key);
            if (loader == null) {
                // The parent keeps what the build tool hides from plugins hidden, such as Gradle's own libraries.
                loader = new URLClassLoader(urls.getURLs(), own.getParent());
            }
            loaders.put(key, loader);
            if (loaders.size() > KEPT) {
                // Not closed: a compilation in another thread may still use it.
                loaders.remove(loaders.keySet().iterator().next());
            }
            return loader;
        }
    }

    /** The jars of the path with their sizes and times; null when an entry is not a jar file. */
    private static List<Object> key(URL[] urls) {
        List<Object> key = new ArrayList<>();
        for (URL url : urls) {
            try {
                if (!url.getProtocol().equals("file")) {
                    return null;
                }
                Path path = Path.of(url.toURI());
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    return null;
                }
                key.add(List.of(path.toString(), attributes.size(), attributes.lastModifiedTime().toMillis()));
            } catch (IOException | URISyntaxException | IllegalArgumentException e) {
                return null;
            }
        }
        return key;
    }

    /** The field of javac's processor class loader, or null when this JVM does not let the plugin set it. */
    private static Field processorLoader() {
        boolean open = ModuleLayer.boot().findModule("jdk.compiler")
                .map(m -> m.isOpen("com.sun.tools.javac.processing", SharedPlugins.class.getModule()))
                .orElse(false);
        if (!open) {
            return null;
        }
        try {
            Field field = JavacProcessingEnvironment.class.getDeclaredField("processorClassLoader");
            field.setAccessible(true);
            return field.getType() == ClassLoader.class ? field : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Makes the kept loader javac's processor class loader once javac has found the annotation processors
     * with its own; javac reports the start of the compilation before it looks for them.
     */
    private static final class UseLoader implements TaskListener {

        private final BasicJavacTask task;
        private final ClassLoader loader;
        private boolean done;

        UseLoader(BasicJavacTask task, ClassLoader loader) {
            this.task = task;
            this.loader = loader;
        }

        @Override
        public void started(TaskEvent event) {
            if (done || event.getKind() == TaskEvent.Kind.COMPILATION) {
                return;
            }
            done = true;
            try {
                PROCESSOR_LOADER.set(JavacProcessingEnvironment.instance(task.getContext()), loader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
