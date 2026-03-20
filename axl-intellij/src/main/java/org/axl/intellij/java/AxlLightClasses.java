package org.axl.intellij.java;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.TypeConversionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlResolver;
import org.axl.intellij.psi.AxlType;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Java view of an .axl file, without building it: the file is translated in memory, and the
 * classes of the generated Java PSI are copied with their nested classes, constructors, methods and
 * fields. Every member navigates to its .axl declaration.
 */
public final class AxlLightClasses {

    private static final List<String> MODIFIERS = List.of(PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE,
            PsiModifier.STATIC, PsiModifier.ABSTRACT, PsiModifier.FINAL);

    private AxlLightClasses() {}

    public static @Nullable PsiClass classOf(AxlFile file) {
        return CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(build(file), file, PsiModificationTracker.MODIFICATION_COUNT));
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

    private static @Nullable PsiClass build(AxlFile file) {
        Translator.Result result = Translator.translate(file.sourcePath(), file.getName(), file.getText(),
                new Options(false).withJavaTypes(new PsiJavaTypes(file)));
        GeneratedUnit unit = result.unit();
        if (unit == null) {
            return null;
        }
        Project project = file.getProject();
        PsiJavaFile java = (PsiJavaFile) PsiFileFactory.getInstance(project)
                .createFileFromText(unit.className() + ".java", com.intellij.lang.java.JavaLanguage.INSTANCE, unit.javaSource());
        // Types in the generated file resolve against the classpath of the .axl file's module.
        java.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, SmartPointerManager.createPointer(file));
        if (java.getClasses().length == 0) {
            return null;
        }
        return new Copy(file, unit.qualifiedName()).of(java.getClasses()[0], unit.qualifiedName(), declaration(file, file.className()), null);
    }

    private static @Nullable AxlTypeDeclaration declaration(AxlFile file, String name) {
        return file.typeDeclarations().stream().filter(d -> name.equals(d.getName())).findFirst().orElse(null);
    }

    /** Copies generated classes into light classes whose members navigate to the declarations of one file. */
    private static final class Copy {
        private final AxlFile file;
        private final String outerName;
        private final PsiManager manager;
        private final PsiElementFactory factory;

        Copy(AxlFile file, String outerName) {
            this.file = file;
            this.outerName = outerName;
            this.manager = PsiManager.getInstance(file.getProject());
            this.factory = JavaPsiFacade.getElementFactory(file.getProject());
        }

        AxlLightClass of(PsiClass generated, String qualifiedName, @Nullable AxlTypeDeclaration declaration, @Nullable PsiClass outer) {
            AxlLightClass light = new AxlLightClass(file, generated, qualifiedName, declaration != null ? declaration : file);
            copyModifiers(generated, light.getModifierList()::addModifier);
            light.setContainingClass(outer);
            references(generated.getExtendsList(), light.getExtendsList()::addReference);
            references(generated.getImplementsList(), light.getImplementsList()::addReference);
            boolean fileLevel = outer == null;
            for (PsiMethod method : generated.getMethods()) {
                light.addMethod(method(method, light, declaration, fileLevel));
            }
            for (PsiField field : generated.getFields()) {
                light.fields.add(field(field, light, declaration, fileLevel));
            }
            for (PsiClass inner : generated.getInnerClasses()) {
                String name = String.valueOf(inner.getName());
                light.inner.add(of(inner, qualifiedName + "." + name, AxlLightClasses.declaration(file, name), light));
            }
            return light;
        }

        private PsiMethod method(PsiMethod method, PsiClass owner, @Nullable AxlTypeDeclaration declaration, boolean fileLevel) {
            LightMethodBuilder builder = new LightMethodBuilder(manager, method.getName());
            builder.setContainingClass(owner);
            builder.setConstructor(method.isConstructor());
            if (!method.isConstructor()) {
                builder.setMethodReturnType(type(method.getReturnType()));
            }
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                builder.addParameter(parameter.getName(), type(parameter.getType()), parameter.isVarArgs());
            }
            copyModifiers(method, builder::addModifier);
            PsiElement source = method.isConstructor() ? declaration : methodSource(method, declaration, fileLevel);
            builder.setNavigationElement(source != null ? source : declaration != null ? declaration : file);
            return builder;
        }

        private PsiField field(PsiField field, PsiClass owner, @Nullable AxlTypeDeclaration declaration, boolean fileLevel) {
            LightFieldBuilder builder = new LightFieldBuilder(manager, field.getName(), type(field.getType()));
            builder.setContainingClass(owner);
            List<String> modifiers = new ArrayList<>();
            copyModifiers(field, modifiers::add);
            builder.setModifiers(modifiers.toArray(String[]::new));
            PsiElement source = variable(declaration, fileLevel, field.getName());
            builder.setNavigationElement(source != null ? source : declaration != null ? declaration : file);
            return builder;
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
         * Types that name classes of this file resolve in the generated file to its own classes; they
         * are written again so that they resolve to the light classes, which Java code sees.
         */
        private PsiType type(PsiType type) {
            String text = type.getCanonicalText();
            int at = text.indexOf(outerName);
            boolean own = at >= 0 && (at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1)))
                    && (at + outerName.length() == text.length() || !Character.isJavaIdentifierPart(text.charAt(at + outerName.length())));
            return own ? factory.createTypeFromText(text, file) : type;
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
            PsiType expected = type == null ? null : AxlResolver.javaType(type);
            if (expected != null
                    && !expected.getCanonicalText().equals(TypeConversionUtil.erasure(parameters[i].getType()).getCanonicalText())) {
                return false;
            }
        }
        return true;
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

    /** A light class with the qualified name of its generated class, which navigates to its declaration. */
    static final class AxlLightClass extends LightPsiClassBuilder {
        private final AxlFile file;
        private final String qualifiedName;
        private final PsiElement declaration;
        private final boolean isInterface;
        private final boolean isEnum;
        private final List<PsiField> fields = new ArrayList<>();
        private final List<PsiClass> inner = new ArrayList<>();

        AxlLightClass(AxlFile file, PsiClass generated, String qualifiedName, PsiElement declaration) {
            super(file, String.valueOf(generated.getName()));
            this.file = file;
            this.qualifiedName = qualifiedName;
            this.declaration = declaration;
            this.isInterface = generated.isInterface();
            this.isEnum = generated.isEnum();
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
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
