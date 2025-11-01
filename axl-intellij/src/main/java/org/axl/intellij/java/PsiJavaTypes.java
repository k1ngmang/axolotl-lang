package org.axl.intellij.java;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.axl.compiler.JavaTypes;
import org.axl.intellij.psi.AxlFile;

/** Types of top-level values from the IDE's Java model instead of javac, so no build is needed. */
public final class PsiJavaTypes implements JavaTypes {

    private final AxlFile context;

    public PsiJavaTypes(AxlFile context) {
        this.context = context;
    }

    @Override
    public Map<String, String> localTypes(String qualifiedClassName, String javaSource) {
        String name = qualifiedClassName.substring(qualifiedClassName.lastIndexOf('.') + 1) + ".java";
        PsiJavaFile java = (PsiJavaFile) PsiFileFactory.getInstance(context.getProject())
                .createFileFromText(name, JavaLanguage.INSTANCE, javaSource);
        java.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, SmartPointerManager.createPointer(context));
        Map<String, String> types = new LinkedHashMap<>();
        for (PsiClass cls : java.getClasses()) {
            for (PsiMethod probe : cls.findMethodsByName(PROBE_METHOD, false)) {
                for (PsiLocalVariable local : PsiTreeUtil.findChildrenOfType(probe, PsiLocalVariable.class)) {
                    PsiType type = local.getType();
                    if (type.isValid() && !type.getCanonicalText().contains("?")) {
                        types.put(local.getName(), type.getCanonicalText());
                    }
                }
            }
        }
        return types;
    }
}
