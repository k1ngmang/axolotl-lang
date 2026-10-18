package org.axl.intellij.java;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationParameterList;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypeParameterListOwner;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightParameterListBuilder;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.light.LightReferenceListBuilder;
import com.intellij.psi.impl.light.LightTypeParameterBuilder;
import com.intellij.psi.impl.light.LightTypeParameterListBuilder;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlResolver;
import org.axl.intellij.psi.AxlType;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlTypeParameter;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Java view of an .axl file, without building it: the file is translated in memory, and the
 * classes of the generated Java PSI are copied with their type parameters, nested classes,
 * constructors, methods, fields and annotations. Every member navigates to its .axl declaration.
 */
public final class AxlLightClasses {

    private static final List<String> MODIFIERS = List.of(PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE,
            PsiModifier.STATIC, PsiModifier.ABSTRACT, PsiModifier.DEFAULT, PsiModifier.FINAL, PsiModifier.SEALED, PsiModifier.NON_SEALED);

    private static final Key<GeneratedUnit> LAST_UNIT = Key.create("axl.lastGeneratedUnit");

    private AxlLightClasses() {}

    public static @Nullable PsiClass classOf(AxlFile file) {
        // Files whose functions take each other's types would build each other's classes forever.
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(
                RecursionManager.doPreventingRecursion(file, false, () -> build(file)), file, PsiModificationTracker.MODIFICATION_COUNT));
    }

    /** The class of a type declaration: the class of its file when the file is named like it, otherwise nested in that class. */
    public static @Nullable PsiClass classOf(AxlTypeDeclaration declaration) {
        if (!(declaration.getContainingFile() instanceof AxlFile file) || declaration.getName() == null) {
            return null;
        }
        PsiClass outer = classOf(file);
        if (outer == null || declaration.getName().equals(file.className())) {
            return outer;
        }
        return outer.findInnerClassByName(declaration.getName(), false);
    }

    /**
     * While the file has errors, its classes come from the translation of the other lines, or failing
     * that, from the last translation that worked, so that its types keep their members.
     */
    private static @Nullable PsiClass build(AxlFile file) {
        Options options = TranslationOptions.of(file);
        Translator.Result result = Translator.translate(file.sourcePath(), file.getName(), file.getText(), options);
        GeneratedUnit unit = result.unit() != null ? result.unit() : withoutErrorLines(file, options, result.diagnostics());
        if (unit == null) {
            unit = file.getUserData(LAST_UNIT);
        }
        if (unit == null) {
            return null;
        }
        file.putUserData(LAST_UNIT, unit);
        Project project = file.getProject();
        PsiJavaFile java = (PsiJavaFile) PsiFileFactory.getInstance(project)
                .createFileFromText(unit.className() + ".java", com.intellij.lang.java.JavaLanguage.INSTANCE, unit.javaSource());
        // Types in the generated file resolve against the classpath of the .axl file's module.
        java.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, SmartPointerManager.createPointer(file));
        if (java.getClasses().length == 0) {
            return null;
        }
        return new Copy(file).of(java.getClasses()[0], unit.qualifiedName(), declaration(file, file.className()));
    }

    /**
     * The translation of the text with the lines that have errors left empty. Of syntax errors only the
     * first counts: the parser's later ones may be about lines it misread after it.
     */
    private static @Nullable GeneratedUnit withoutErrorLines(AxlFile file, Options options, List<AxlDiagnostic> diagnostics) {
        List<String> lines = new ArrayList<>(List.of(file.getText().split("\n", -1)));
        for (int attempt = 0; attempt < 3; attempt++) {
            List<AxlDiagnostic> syntax = diagnostics.stream().filter(d -> d.code() == ErrorCode.SYNTAX).limit(1).toList();
            for (AxlDiagnostic d : syntax.isEmpty() ? diagnostics : syntax) {
                if (d.isError() && d.line() >= 1 && d.line() <= lines.size()) {
                    lines.set(d.line() - 1, "");
                }
            }
            Translator.Result result = Translator.translate(file.sourcePath(), file.getName(), String.join("\n", lines), options.asDraft());
            if (result.unit() != null) {
                return result.unit();
            }
            diagnostics = result.diagnostics();
        }
        return null;
    }

    private static @Nullable AxlTypeDeclaration declaration(AxlFile file, String name) {
        return file.typeDeclarations().stream().filter(d -> name.equals(d.getName())).findFirst().orElse(null);
    }

    /** Copies generated classes into light classes whose members navigate to the declarations of one file. */
    private static final class Copy {
        private final AxlFile file;
        private final PsiManager manager;
        private final PsiElementFactory factory;
        // The light class of each generated class and the light type parameter of each generated one: what the types of the members name.
        private final Map<PsiClass, AxlLightClass> classes = new HashMap<>();
        private final Map<PsiTypeParameter, AxlLightTypeParameter> typeParameters = new HashMap<>();

        Copy(AxlFile file) {
            this.file = file;
            this.manager = PsiManager.getInstance(file.getProject());
            this.factory = JavaPsiFacade.getElementFactory(file.getProject());
        }

        /** The classes are all created before any member, since the type of a member may name a class declared later in the file. */
        AxlLightClass of(PsiClass generated, String qualifiedName, @Nullable AxlTypeDeclaration declaration) {
            AxlLightClass light = shell(generated, qualifiedName, declaration, null);
            fill(light);
            return light;
        }

        private AxlLightClass shell(PsiClass generated, String qualifiedName, @Nullable AxlTypeDeclaration declaration, @Nullable PsiClass outer) {
            AxlLightClass light = new AxlLightClass(file, generated, qualifiedName, declaration != null ? declaration : file,
                    modifiers(generated));
            light.setContainingClass(outer);
            classes.put(generated, light);
            declareTypeParameters(generated, light, light.getTypeParameterList());
            for (PsiClass inner : generated.getInnerClasses()) {
                String name = String.valueOf(inner.getName());
                light.inner.add(shell(inner, qualifiedName + "." + name, AxlLightClasses.declaration(file, name), light));
            }
            return light;
        }

        private void fill(AxlLightClass light) {
            PsiClass generated = light.generated;
            AxlTypeDeclaration declaration = light.declaration instanceof AxlTypeDeclaration type ? type : null;
            bounds(generated);
            references(generated.getExtendsList(), light.getExtendsList()::addReference);
            references(generated.getImplementsList(), light.getImplementsList()::addReference);
            // Kept as written: resolving them would build the classes of the files that implement this one, which need it.
            if (generated.getPermitsList() != null) {
                for (PsiClassType type : generated.getPermitsList().getReferencedTypes()) {
                    light.getPermitsList().addReference(type);
                }
            }
            boolean fileLevel = light.getContainingClass() == null;
            for (PsiMethod method : generated.getMethods()) {
                light.addMethod(method(method, light, declaration, fileLevel));
            }
            for (PsiField field : generated.getFields()) {
                light.fields.add(field(field, light, declaration, fileLevel));
            }
            light.inner.forEach(this::fill);
        }

        /**
         * The type parameters of a generated class or method, declared again on the light one, which
         * the types of its members name. Their bounds follow once everything they may name exists.
         */
        private void declareTypeParameters(PsiTypeParameterListOwner generated, PsiTypeParameterListOwner light,
                                           LightTypeParameterListBuilder list) {
            PsiTypeParameter[] parameters = generated.getTypeParameters();
            for (int i = 0; i < parameters.length; i++) {
                AxlLightTypeParameter parameter = new AxlLightTypeParameter(String.valueOf(parameters[i].getName()), light, i);
                typeParameters.put(parameters[i], parameter);
                list.addParameter(parameter);
            }
        }

        private void bounds(PsiTypeParameterListOwner generated) {
            for (PsiTypeParameter parameter : generated.getTypeParameters()) {
                references(parameter.getExtendsList(), typeParameters.get(parameter).getExtendsList()::addReference);
            }
        }

        private PsiMethod method(PsiMethod method, PsiClass owner, @Nullable AxlTypeDeclaration declaration, boolean fileLevel) {
            LightTypeParameterListBuilder typeParameterList = new LightTypeParameterListBuilder(manager, JavaLanguage.INSTANCE);
            AxlLightMethod builder = new AxlLightMethod(manager, method.getName(), modifiers(method), typeParameterList);
            builder.setContainingClass(owner);
            builder.setConstructor(method.isConstructor());
            declareTypeParameters(method, builder, typeParameterList);
            bounds(method);
            if (!method.isConstructor()) {
                builder.setMethodReturnType(type(method.getReturnType()));
            }
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                builder.addParameter(new LightParameter(parameter.getName(), type(parameter.getType()), builder, JavaLanguage.INSTANCE,
                        modifiers(parameter), parameter.isVarArgs()));
            }
            PsiElement source = method.isConstructor() ? constructorSource(method, declaration) : methodSource(method, declaration, fileLevel);
            builder.setNavigationElement(source != null ? source : declaration != null ? declaration : file);
            return builder;
        }

        private PsiField field(PsiField field, PsiClass owner, @Nullable AxlTypeDeclaration declaration, boolean fileLevel) {
            LightFieldBuilder builder = new LightFieldBuilder(manager, field.getName(), type(field.getType()));
            builder.setContainingClass(owner);
            builder.setModifierList(modifiers(field));
            PsiElement source = variable(declaration, fileLevel, field.getName());
            builder.setNavigationElement(source != null ? source : declaration != null ? declaration : file);
            return builder;
        }

        /**
         * The modifiers and annotations of a generated element. An annotation is written again by the
         * qualified name it resolves to in the generated file, so that `@Test` is JUnit's wherever the
         * light element is looked at, and with the .axl file as its context, so that it resolves there.
         */
        private AxlLightModifierList modifiers(PsiModifierListOwner generated) {
            List<PsiAnnotation> annotations = new ArrayList<>();
            PsiModifierList list = generated.getModifierList();
            for (PsiAnnotation annotation : list == null ? PsiAnnotation.EMPTY_ARRAY : list.getAnnotations()) {
                String name = annotation.getQualifiedName();
                if (name == null) {
                    continue;
                }
                PsiAnnotationParameterList arguments = annotation.getParameterList();
                String text = "@" + name + (arguments.getAttributes().length == 0 ? "" : arguments.getText());
                annotations.add(factory.createAnnotationFromText(text, file));
            }
            AxlLightModifierList modifiers = new AxlLightModifierList(manager, annotations);
            copyModifiers(generated, modifiers::addModifier);
            return modifiers;
        }

        /** The `constructor(...)` of the class whose parameters have the names of a generated one, else the class itself. */
        private @Nullable PsiElement constructorSource(PsiMethod method, @Nullable AxlTypeDeclaration declaration) {
            List<String> names = Arrays.stream(method.getParameterList().getParameters()).map(PsiParameter::getName).toList();
            for (PsiElement member : declaration == null ? PsiElement.EMPTY_ARRAY : declaration.getChildren()) {
                if (member.getNode().getElementType() == AxlElementTypes.CONSTRUCTOR && parameterNames(member).equals(names)) {
                    return member;
                }
            }
            return declaration;
        }

        /** The names of the parameters of a `constructor(...)`. */
        private static List<String> parameterNames(PsiElement constructor) {
            List<String> names = new ArrayList<>();
            for (PsiElement part : constructor.getChildren()) {
                if (part.getNode().getElementType() == AxlElementTypes.PARAMETER_LIST) {
                    PsiTreeUtil.getChildrenOfTypeAsList(part, AxlVariable.class).forEach(p -> names.add(p.getName()));
                }
            }
            return names;
        }

        /** A method of the type, the accessor of a field, or a function of the file for its own class. */
        private @Nullable PsiElement methodSource(PsiMethod method, @Nullable AxlTypeDeclaration declaration, boolean fileLevel) {
            List<AxlFunction> functions = new ArrayList<>(declaration != null ? declaration.methods() : List.of());
            if (fileLevel) {
                functions.addAll(file.functions());
            }
            AxlFunction function = function(functions, method);
            if (function != null || declaration == null || method.getParameterList().getParametersCount() > 0) {
                return function;
            }
            return declaration.fields().stream().filter(f -> method.getName().equals(f.getName())).findFirst().orElse(null);
        }

        private @Nullable AxlVariable variable(@Nullable AxlTypeDeclaration declaration, boolean fileLevel, String name) {
            List<AxlVariable> variables = new ArrayList<>(declaration != null ? declaration.fields() : List.of());
            if (fileLevel) {
                variables.addAll(file.values());
            }
            return variables.stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
        }

        private void references(@Nullable PsiReferenceList list, Consumer<PsiClassType> add) {
            if (list == null) {
                return;
            }
            for (PsiClassType type : list.getReferencedTypes()) {
                add.accept(type(type) instanceof PsiClassType mapped ? mapped : type);
            }
        }

        /**
         * Types that name classes and type parameters of this file resolve in the generated file to
         * its own; they are written again so that they name the light ones, which Java code sees.
         */
        private PsiType type(PsiType type) {
            if (type instanceof PsiArrayType array) {
                PsiType component = type(array.getComponentType());
                if (component == array.getComponentType()) {
                    return type;
                }
                return (array instanceof PsiEllipsisType ? new PsiEllipsisType(component) : new PsiArrayType(component))
                        .annotate(type.getAnnotationProvider());
            }
            if (type instanceof PsiWildcardType wildcard && wildcard.getBound() != null) {
                PsiType bound = type(wildcard.getBound());
                if (bound == wildcard.getBound()) {
                    return type;
                }
                return wildcard.isExtends() ? PsiWildcardType.createExtends(manager, bound) : PsiWildcardType.createSuper(manager, bound);
            }
            if (!(type instanceof PsiClassType classType)) {
                return type;
            }
            PsiClass resolved = classType.resolve();
            PsiClass light = resolved instanceof PsiTypeParameter parameter ? typeParameters.get(parameter) : classes.get(resolved);
            PsiType[] arguments = classType.getParameters();
            PsiType[] mapped = new PsiType[arguments.length];
            boolean changed = light != null;
            for (int i = 0; i < arguments.length; i++) {
                mapped[i] = type(arguments[i]);
                changed |= mapped[i] != arguments[i];
            }
            if (!changed || resolved == null) {
                return type;
            }
            return factory.createType(light != null ? light : resolved, mapped).annotate(classType.getAnnotationProvider());
        }
    }

    /**
     * A reference list that keeps the types it is given: the platform's own re-creates each from its
     * class, without the type arguments, and keeps the types of its first reading.
     */
    static final class AxlLightReferenceList extends LightReferenceListBuilder {
        private final List<PsiClassType> types = new ArrayList<>();

        AxlLightReferenceList(PsiManager manager, PsiReferenceList.Role role) {
            super(manager, JavaLanguage.INSTANCE, role);
        }

        @Override
        public void addReference(PsiClassType type) {
            types.add(type);
        }

        @Override
        public PsiClassType @NotNull [] getReferencedTypes() {
            return types.toArray(PsiClassType.EMPTY_ARRAY);
        }

        @Override
        public PsiJavaCodeReferenceElement @NotNull [] getReferenceElements() {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(getProject());
            return types.stream().map(factory::createReferenceElementByType).toArray(PsiJavaCodeReferenceElement[]::new);
        }
    }

    /**
     * A light method whose context is its class, which Java's inference of `new Pair<>(...)` looks
     * for; the platform's own would lead to the .axl declaration it navigates to.
     */
    static final class AxlLightMethod extends LightMethodBuilder {
        AxlLightMethod(PsiManager manager, String name, PsiModifierList modifiers, LightTypeParameterListBuilder typeParameters) {
            super(manager, JavaLanguage.INSTANCE, name, new LightParameterListBuilder(manager, JavaLanguage.INSTANCE), modifiers,
                    new LightReferenceListBuilder(manager, JavaLanguage.INSTANCE, PsiReferenceList.Role.THROWS_LIST), typeParameters);
        }

        @Override
        public PsiElement getContext() {
            return getContainingClass();
        }
    }

    /** A light type parameter whose bounds keep their type arguments: `T extends Comparable<T>`. */
    static final class AxlLightTypeParameter extends LightTypeParameterBuilder {
        private final AxlLightReferenceList bounds;

        AxlLightTypeParameter(String name, PsiTypeParameterListOwner owner, int index) {
            super(name, owner, index);
            this.bounds = new AxlLightReferenceList(getManager(), PsiReferenceList.Role.EXTENDS_BOUNDS_LIST);
        }

        @Override
        public AxlLightReferenceList getExtendsList() {
            return bounds;
        }
    }

    private static void copyModifiers(PsiModifierListOwner from, Consumer<String> add) {
        for (String modifier : MODIFIERS) {
            if (from.hasModifierProperty(modifier)) {
                add.accept(modifier);
            }
        }
    }

    /** The function a generated method comes from: by name and parameter types. */
    private static @Nullable AxlFunction function(List<AxlFunction> functions, PsiMethod method) {
        AxlFunction sameCount = null;
        for (AxlFunction function : functions) {
            if (!method.getName().equals(function.getName())) {
                continue;
            }
            if (function.isMain() && isJavaMain(method)) {
                return function;
            }
            if (function.parameters().size() == method.getParameterList().getParametersCount()) {
                if (sameParameterTypes(function, method)) {
                    return function;
                }
                if (sameCount == null) {
                    sameCount = function;
                }
            }
        }
        return sameCount;
    }

    /** `fn main()` becomes `main(String[] args)`. */
    private static boolean isJavaMain(PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        return parameters.length == 1 && parameters[0].getType().getCanonicalText().equals("java.lang.String[]");
    }

    private static boolean sameParameterTypes(AxlFunction function, PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            AxlType type = function.parameters().get(i).type();
            String expected = type == null ? null : erasedName(type);
            if (expected != null && !expected.equals(TypeConversionUtil.erasure(parameters[i].getType()).getCanonicalText())) {
                return false;
            }
        }
        return true;
    }

    /** The qualified name of a written type; a type of the file itself is named without building its class. */
    private static @Nullable String erasedName(AxlType type) {
        var reference = type.reference();
        PsiElement target = reference == null || reference.getReference() == null ? null : reference.getReference().resolve();
        if (target instanceof AxlTypeDeclaration declaration && declaration.getContainingFile() instanceof AxlFile file) {
            String name = declaration.getName();
            return file.className().equals(name) ? file.qualifiedClassName() : file.qualifiedClassName() + "." + name;
        }
        if (target instanceof AxlTypeParameter parameter) {
            // A `T` erases to its bound; named without the light form of its owner, which is being built.
            return parameter.bound() == null ? "java.lang.Object" : erasedName(parameter.bound());
        }
        PsiType java = AxlResolver.javaType(type);
        return java == null ? null : java.getCanonicalText();
    }

    /** The light method of a function or method, which Java code calls. */
    public static @Nullable PsiMethod methodOf(AxlFunction function) {
        PsiClass cls = ownerOf(function);
        if (cls == null) {
            return null;
        }
        for (PsiMethod method : cls.findMethodsByName(function.getName(), false)) {
            if (method.getNavigationElement() == function) {
                return method;
            }
        }
        return null;
    }

    /** The light type parameter of a declared one, which the types of the light members name; null while its owner cannot be built. */
    public static @Nullable PsiTypeParameter typeParameterOf(AxlTypeParameter parameter) {
        PsiTypeParameterListOwner owner = switch (parameter.getParent()) {
            case AxlFunction function -> methodOf(function);
            case AxlTypeDeclaration declaration -> classOf(declaration);
            default -> null;
        };
        if (owner == null) {
            return null;
        }
        return Arrays.stream(owner.getTypeParameters()).filter(t -> Objects.equals(t.getName(), parameter.getName())).findFirst().orElse(null);
    }

    /**
     * What Java code refers to for a value: the field of a top-level value or of a class property,
     * the accessor of a data class field or of an interface member, the constant of an enum.
     */
    public static List<PsiMember> membersOf(AxlVariable variable) {
        boolean member = variable.isTopLevel() || variable.getParent() instanceof AxlTypeDeclaration;
        PsiClass cls = member ? ownerOf(variable) : null;
        if (cls == null || variable.getName() == null) {
            return List.of();
        }
        List<PsiMember> result = new ArrayList<>();
        PsiField field = cls.findFieldByName(variable.getName(), false);
        if (field != null && field.getNavigationElement() == variable) {
            result.add(field);
        }
        for (PsiMethod method : cls.findMethodsByName(variable.getName(), false)) {
            if (method.getNavigationElement() == variable) {
                result.add(method);
            }
        }
        return result;
    }

    private static @Nullable PsiClass ownerOf(PsiElement member) {
        if (member.getParent() instanceof AxlTypeDeclaration declaration) {
            return classOf(declaration);
        }
        return member.getParent() instanceof AxlFile file ? classOf(file) : null;
    }

    /** A modifier list with the annotations given to it; the platform's own rejects added annotations. */
    static final class AxlLightModifierList extends LightModifierList {
        private final List<PsiAnnotation> annotations;

        AxlLightModifierList(PsiManager manager, List<PsiAnnotation> annotations) {
            super(manager, JavaLanguage.INSTANCE);
            this.annotations = annotations;
        }

        @Override
        public PsiAnnotation @NotNull [] getAnnotations() {
            return annotations.toArray(PsiAnnotation.EMPTY_ARRAY);
        }

        @Override
        public PsiAnnotation @NotNull [] getApplicableAnnotations() {
            return getAnnotations();
        }

        @Override
        public @Nullable PsiAnnotation findAnnotation(@NotNull String qualifiedName) {
            return annotations.stream().filter(a -> qualifiedName.equals(a.getQualifiedName())).findFirst().orElse(null);
        }

        @Override
        public boolean hasAnnotation(@NotNull String qualifiedName) {
            return findAnnotation(qualifiedName) != null;
        }

        @Override
        public String getText() {
            StringBuilder text = new StringBuilder();
            annotations.forEach(a -> text.append(a.getText()).append(' '));
            return text.append(super.getText()).toString();
        }
    }

    /** A light class with the qualified name of its generated class, which navigates to its declaration. */
    static final class AxlLightClass extends LightPsiClassBuilder {
        private final AxlFile file;
        private final PsiClass generated;
        private final String qualifiedName;
        private final PsiElement declaration;
        private final boolean isInterface;
        private final boolean isEnum;
        private final AxlLightModifierList modifiers;
        private final AxlLightReferenceList extendsList;
        private final AxlLightReferenceList implementsList;
        private final AxlLightReferenceList permitsList;
        private final List<PsiField> fields = new ArrayList<>();
        private final List<AxlLightClass> inner = new ArrayList<>();

        AxlLightClass(AxlFile file, PsiClass generated, String qualifiedName, PsiElement declaration, AxlLightModifierList modifiers) {
            super(file, String.valueOf(generated.getName()));
            this.file = file;
            this.generated = generated;
            this.qualifiedName = qualifiedName;
            this.declaration = declaration;
            this.isInterface = generated.isInterface();
            this.isEnum = generated.isEnum();
            this.modifiers = modifiers;
            this.extendsList = new AxlLightReferenceList(getManager(), PsiReferenceList.Role.EXTENDS_LIST);
            this.implementsList = new AxlLightReferenceList(getManager(), PsiReferenceList.Role.IMPLEMENTS_LIST);
            this.permitsList = new AxlLightReferenceList(getManager(), PsiReferenceList.Role.PERMITS_LIST);
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public @NotNull LightModifierList getModifierList() {
            return modifiers;
        }

        @Override
        public AxlLightReferenceList getExtendsList() {
            return extendsList;
        }

        @Override
        public AxlLightReferenceList getImplementsList() {
            return implementsList;
        }

        /** The implementations a sealed interface names when other files of its package declare some. */
        @Override
        public AxlLightReferenceList getPermitsList() {
            return permitsList;
        }

        @Override
        public boolean isInterface() {
            return isInterface;
        }

        @Override
        public boolean isEnum() {
            return isEnum;
        }

        @Override
        public @NotNull PsiElement getNavigationElement() {
            return declaration;
        }

        /** The class around a nested one, or the file: where Java's own code looks from a member outwards. */
        @Override
        public PsiElement getContext() {
            PsiClass outer = getContainingClass();
            return outer != null ? outer : file;
        }

        @Override
        public com.intellij.psi.PsiFile getContainingFile() {
            return file;
        }

        @Override
        public boolean isValid() {
            return file.isValid();
        }

        @Override
        public PsiField @NotNull [] getFields() {
            return fields.toArray(PsiField.EMPTY_ARRAY);
        }

        @Override
        public PsiClass @NotNull [] getInnerClasses() {
            return inner.toArray(PsiClass.EMPTY_ARRAY);
        }
    }
}
