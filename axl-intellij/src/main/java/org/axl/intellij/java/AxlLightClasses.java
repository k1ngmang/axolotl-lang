package org.axl.intellij.java;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlResolver;
import org.axl.intellij.psi.AxlType;
import org.axl.intellij.psi.AxlVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Java view of an .axl file, without building it: the file is translated in memory, javac's
 * types are read from the generated Java PSI, and every member navigates to its .axl declaration.
 */
public final class AxlLightClasses {

    private AxlLightClasses() {}

    public static @Nullable PsiClass classOf(AxlFile file) {
        return CachedValuesManager.getCachedValue(file, () ->
                CachedValueProvider.Result.create(build(file), file, PsiModificationTracker.MODIFICATION_COUNT));
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
        PsiClass generated = java.getClasses().length == 0 ? null : java.getClasses()[0];
        if (generated == null) {
            return null;
        }
        AxlLightClass light = new AxlLightClass(file, unit.className(), unit.qualifiedName());
        light.getModifierList().addModifier(PsiModifier.PUBLIC);
        light.getModifierList().addModifier(PsiModifier.FINAL);
        PsiManager manager = PsiManager.getInstance(project);
        for (PsiMethod method : generated.getMethods()) {
            if (method.isConstructor()) {
                continue;
            }
            LightMethodBuilder builder = new LightMethodBuilder(manager, method.getName());
            builder.setContainingClass(light);
            builder.setMethodReturnType(method.getReturnType());
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                builder.addParameter(parameter.getName(), parameter.getType());
            }
            builder.addModifiers(method.hasModifierProperty(PsiModifier.PRIVATE) ? PsiModifier.PRIVATE : PsiModifier.PUBLIC,
                    PsiModifier.STATIC);
            PsiElement declaration = function(file, method);
            builder.setNavigationElement(declaration != null ? declaration : file);
            light.addMethod(builder);
        }
        for (PsiField field : generated.getFields()) {
            LightFieldBuilder builder = new LightFieldBuilder(manager, field.getName(), field.getType());
            builder.setContainingClass(light);
            builder.setModifiers(field.hasModifierProperty(PsiModifier.PRIVATE) ? PsiModifier.PRIVATE : PsiModifier.PUBLIC,
                    PsiModifier.STATIC, PsiModifier.FINAL);
            PsiElement declaration = value(file, field.getName());
            builder.setNavigationElement(declaration != null ? declaration : file);
            light.fields.add(builder);
        }
        return light;
    }

    /** The function a generated method comes from: by name and parameter types. */
    private static @Nullable AxlFunction function(AxlFile file, PsiMethod method) {
        AxlFunction sameCount = null;
        for (AxlFunction function : file.functions()) {
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
            String name = type == null ? null : type.name().substring(type.name().lastIndexOf('.') + 1);
            if (name != null && !name.equals(AxlResolver.simpleName(parameters[i].getType()))) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable AxlVariable value(AxlFile file, String name) {
        return file.values().stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    /** The light method of a function, which Java code calls. */
    public static @Nullable PsiMethod methodOf(AxlFunction function) {
        if (!(function.getContainingFile() instanceof AxlFile file)) {
            return null;
        }
        PsiClass cls = classOf(file);
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

    public static @Nullable PsiField fieldOf(AxlVariable value) {
        if (!(value.getContainingFile() instanceof AxlFile file) || !value.isTopLevel()) {
            return null;
        }
        PsiClass cls = classOf(file);
        return cls == null ? null : cls.findFieldByName(value.getName(), false);
    }

    /** A light class with the qualified name of the .axl file that navigates to the file. */
    static final class AxlLightClass extends LightPsiClassBuilder {
        private final AxlFile file;
        private final String qualifiedName;
        private final java.util.List<PsiField> fields = new java.util.ArrayList<>();

        AxlLightClass(AxlFile file, String name, String qualifiedName) {
            super(file, name);
            this.file = file;
            this.qualifiedName = qualifiedName;
        }

        @Override
        public String getQualifiedName() {
            return qualifiedName;
        }

        @Override
        public @NotNull PsiElement getNavigationElement() {
            return file;
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
    }
}
