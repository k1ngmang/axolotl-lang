package org.axl.intellij.java;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypeParameterListOwner;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.ReflectedClasses;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.Nullable;

/**
 * The classes of a module's classpath from the IDE's Java model, as the build reads them with javac: the
 * libraries, the Java sources of the module and of the modules it depends on, and the module's JDK. A class
 * the model does not have, as in a module without a JDK, is read from the IDE's own JDK. One instance per
 * module serves all its files and keeps the answers until a Java file or the module's roots change.
 */
public final class PsiJavaClasses implements JavaClasses {

    private static final Key<CachedValue<PsiJavaClasses>> PRODUCTION = Key.create("axl.productionClasses");
    private static final Key<CachedValue<PsiJavaClasses>> TESTS = Key.create("axl.testClasses");

    private final Project project;
    private final GlobalSearchScope scope;
    // An empty Optional is a class that cannot be read, which the map cannot hold as null.
    private final Map<String, Optional<ClassShape>> shapes = new ConcurrentHashMap<>();
    private final Map<String, List<MethodShape>> methods = new ConcurrentHashMap<>();
    private final Map<String, List<Signature>> signatures = new ConcurrentHashMap<>();

    private PsiJavaClasses(Module module, boolean tests) {
        this.project = module.getProject();
        this.scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, tests);
    }

    /** The classes the file's module reads, with its test classpath for a test file; the JDK alone outside a module or while indexing. */
    public static JavaClasses of(AxlFile file) {
        Project project = file.getProject();
        Module module = ModuleUtilCore.findModuleForPsiElement(file);
        VirtualFile own = file.getOriginalFile().getVirtualFile();
        if (module == null || own == null || DumbService.isDumb(project)) {
            return ReflectedClasses.JDK;
        }
        boolean tests = ProjectFileIndex.getInstance(project).isInTestSourceContent(own);
        return CachedValuesManager.getManager(project).getCachedValue(module, tests ? TESTS : PRODUCTION,
                () -> CachedValueProvider.Result.create(new PsiJavaClasses(module, tests), ProjectRootModificationTracker.getInstance(project),
                        PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE)), false);
    }

    @Override
    public @Nullable ClassShape shape(String qualifiedName) {
        Optional<ClassShape> known = shapes.get(qualifiedName);
        if (known != null) {
            return known.orElse(null);
        }
        Optional<ClassShape> read = read(() -> {
            PsiClass cls = find(qualifiedName);
            return Optional.ofNullable(cls == null ? ReflectedClasses.JDK.shape(qualifiedName) : shapeOf(cls));
        });
        if (read == null) {
            return ReflectedClasses.JDK.shape(qualifiedName);
        }
        shapes.put(qualifiedName, read);
        return read.orElse(null);
    }

    @Override
    public List<MethodShape> methods(String qualifiedName) {
        List<MethodShape> known = methods.get(qualifiedName);
        if (known != null) {
            return known;
        }
        List<MethodShape> read = read(() -> {
            PsiClass cls = find(qualifiedName);
            return cls == null ? ReflectedClasses.JDK.methods(qualifiedName) : methodsOf(cls);
        });
        if (read == null) {
            return ReflectedClasses.JDK.methods(qualifiedName);
        }
        methods.put(qualifiedName, read);
        return read;
    }

    @Override
    public List<Signature> signatures(String qualifiedName, String name) {
        String key = qualifiedName + "#" + name;
        List<Signature> known = signatures.get(key);
        if (known != null) {
            return known;
        }
        List<Signature> read = read(() -> {
            PsiClass cls = find(qualifiedName);
            return cls == null ? ReflectedClasses.JDK.signatures(qualifiedName, name) : signaturesOf(cls, name);
        });
        if (read == null) {
            return ReflectedClasses.JDK.signatures(qualifiedName, name);
        }
        signatures.put(key, read);
        return read;
    }

    /**
     * The translator asks on a thread of its own while its caller holds the read lock, so the model is read
     * in a read action of this thread; it cannot wait for a pending write action, which waits for the caller,
     * so the caller is canceled instead. Null while the indexes are being built: the answer is not kept then.
     */
    private <T> @Nullable T read(Supplier<T> question) {
        List<T> answer = new ArrayList<>(1);
        boolean[] indexing = {false};
        Runnable run = () -> {
            try {
                answer.add(question.get());
            } catch (IndexNotReadyException e) {
                indexing[0] = true;
            }
        };
        if (!ApplicationManagerEx.getApplicationEx().tryRunReadAction(run)) {
            throw new ProcessCanceledException();
        }
        return indexing[0] ? null : answer.getFirst();
    }

    /** The class of the classpath, without the classes of .axl files, whose translation may ask for this one. */
    private @Nullable PsiClass find(String qualifiedName) {
        for (PsiElementFinder finder : PsiElementFinder.EP.getExtensions(project)) {
            if (!(finder instanceof AxlClassFinder)) {
                PsiClass found = finder.findClass(qualifiedName, scope);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ClassShape shapeOf(PsiClass cls) {
        List<String> typeParameters = Arrays.stream(cls.getTypeParameters()).map(PsiTypeParameter::getName).toList();
        List<Supertype> supertypes = new ArrayList<>();
        for (PsiClassType type : cls.isInterface() ? cls.getExtendsListTypes() : cls.getImplementsListTypes()) {
            supertypes.add(supertype(type, typeParameters));
        }
        if (!cls.isInterface() && !"java.lang.Object".equals(cls.getQualifiedName())) {
            PsiClassType[] extended = cls.getExtendsListTypes();
            String implicit = cls.isEnum() ? "java.lang.Enum" : cls.isRecord() ? "java.lang.Record" : "java.lang.Object";
            supertypes.add(extended.length > 0 ? supertype(extended[0], typeParameters) : new Supertype(implicit, List.of()));
        }
        List<String> components = Arrays.stream(cls.getRecordComponents()).map(PsiRecordComponent::getName).toList();
        List<String> constants = Arrays.stream(cls.getFields()).filter(f -> f instanceof PsiEnumConstant).map(PsiField::getName).toList();
        return new ClassShape(cls.isInterface(), typeParameters, supertypes, components, permitted(cls), constants);
    }

    /** The supertype with, where it is written with a type parameter of the class, the name of that parameter. */
    private static Supertype supertype(PsiClassType type, List<String> typeParameters) {
        List<String> arguments = new ArrayList<>();
        for (PsiType argument : type.getParameters()) {
            String name = argument instanceof PsiClassType written && !written.hasParameters() ? written.getClassName() : null;
            arguments.add(name != null && typeParameters.contains(name) ? name : "");
        }
        return new Supertype(qualifiedName(type), arguments);
    }

    /** What a sealed class permits: the classes it names, or without a `permits` clause those of its file that extend it. */
    private static List<String> permitted(PsiClass cls) {
        if (!cls.hasModifierProperty(PsiModifier.SEALED)) {
            return List.of();
        }
        PsiClassType[] named = cls.getPermitsListTypes();
        if (named.length > 0) {
            return Arrays.stream(named).map(PsiJavaClasses::qualifiedName).toList();
        }
        return PsiTreeUtil.findChildrenOfType(cls.getContainingFile(), PsiClass.class).stream()
                .filter(c -> c.isInheritor(cls, false) && c.getQualifiedName() != null).map(PsiClass::getQualifiedName).toList();
    }

    private static List<MethodShape> methodsOf(PsiClass cls) {
        if (!cls.isInterface()) {
            return List.of();
        }
        List<MethodShape> shapes = new ArrayList<>();
        for (PsiMethod method : cls.getAllMethods()) {
            PsiClass owner = method.getContainingClass();
            // The methods of Object are among those of an interface as well.
            if (owner == null || !owner.isInterface() || method.hasModifierProperty(PsiModifier.STATIC) || method.isConstructor()) {
                continue;
            }
            List<Boolean> parameters = Arrays.stream(method.getParameterList().getParameters())
                    .map(p -> typeParameter(p.getType(), owner) != null).toList();
            PsiType result = method.getReturnType();
            boolean typeVariable = result != null && (typeParameter(result, owner) != null || typeParameter(result, method) != null);
            shapes.add(new MethodShape(method.getName(), parameters, typeVariable));
        }
        return shapes;
    }

    private static List<Signature> signaturesOf(PsiClass cls, String name) {
        boolean constructors = name.equals("<init>");
        PsiMethod[] candidates = constructors ? cls.getConstructors() : cls.getAllMethods();
        List<Signature> found = new ArrayList<>();
        for (PsiMethod method : candidates) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && (constructors || method.getName().equals(name) && !method.isConstructor())) {
                List<String> parameters = Arrays.stream(method.getParameterList().getParameters())
                        .map(p -> erased(p.getType(), method)).toList();
                found.add(new Signature(parameters, method.isVarArgs(), !constructors && PsiTypes.voidType().equals(method.getReturnType())));
            }
        }
        return found;
    }

    /**
     * The type parameter of the owner a type names. Only a name such a parameter has is resolved: resolving
     * another one may translate an .axl file, which may be the one asking.
     */
    private static @Nullable PsiTypeParameter typeParameter(PsiType type, PsiTypeParameterListOwner owner) {
        if (!(type instanceof PsiClassType classType) || classType.hasParameters()) {
            return null;
        }
        Set<String> names = Arrays.stream(owner.getTypeParameters()).map(PsiTypeParameter::getName).collect(Collectors.toSet());
        if (!names.contains(classType.getClassName())) {
            return null;
        }
        return classType.resolve() instanceof PsiTypeParameter parameter && parameter.getOwner() == owner ? parameter : null;
    }

    /** As javac erases a parameter, `java.lang.String[]`, but with the name of a type variable, `T[]`. */
    private static String erased(PsiType type, PsiMethod method) {
        if (type instanceof PsiArrayType array) {
            return erased(array.getComponentType(), method) + "[]";
        }
        if (type instanceof PsiClassType classType) {
            PsiClass owner = method.getContainingClass();
            if (typeParameter(type, method) != null || owner != null && typeParameter(type, owner) != null) {
                return classType.getClassName();
            }
            return qualifiedName(classType);
        }
        return type.getCanonicalText();
    }

    private static String qualifiedName(PsiClassType type) {
        PsiClass cls = type.resolve();
        String name = cls == null ? null : cls.getQualifiedName();
        return name != null ? name : type.rawType().getCanonicalText();
    }
}
