package org.axl.intellij.run;

import com.intellij.execution.PsiLocation;
import com.intellij.execution.junit.JUnitUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import org.axl.intellij.java.AxlLightClasses;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.jetbrains.annotations.Nullable;

/** Test classes and methods of .axl files, as JUnit sees their light classes: `class T { @Test fn x() {} }`. */
final class AxlTests {

    private AxlTests() {}

    /** The light class of a declaration when JUnit would run it. */
    static @Nullable PsiClass testClass(AxlTypeDeclaration declaration) {
        PsiClass cls = AxlLightClasses.classOf(declaration);
        return cls != null && JUnitUtil.isTestClass(cls) ? cls : null;
    }

    /** The light method of a function when JUnit would run it. */
    static @Nullable PsiMethod testMethod(AxlFunction function) {
        PsiMethod method = AxlLightClasses.methodOf(function);
        return method != null && method.getContainingClass() != null && JUnitUtil.isTestMethod(PsiLocation.fromPsiElement(method))
                ? method : null;
    }
}
