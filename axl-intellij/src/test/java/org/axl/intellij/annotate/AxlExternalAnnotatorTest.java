package org.axl.intellij.annotate;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.util.List;

public class AxlExternalAnnotatorTest extends LightJavaCodeInsightFixtureTestCase {

    public void testLeavesTheErrorsToTheLanguageServerOnlyWhileItRuns() {
        myFixture.configureByText("Main.axl", "package app\n");
        assertFalse(AxlServer.languageServerRuns(getProject()));
        try {
            AxlServer.useLanguageServer(getProject(), () -> false);
            assertFalse(AxlServer.languageServerRuns(getProject()));
            AxlServer.useLanguageServer(getProject(), () -> true);
            assertTrue(AxlServer.languageServerRuns(getProject()));
        } finally {
            AxlServer.useLanguageServer(getProject(), null);
        }
    }

    public void testChecksATestWithTheTestAndProductionRoots() throws IOException {
        VirtualFile main = myFixture.addFileToProject("app/Main.axl", "package app\n").getVirtualFile();
        VirtualFile tests = myFixture.getTempDirFixture().findOrCreateDir("tests");
        PsiTestUtil.addSourceRoot(getModule(), tests, true);
        try {
            VirtualFile test = myFixture.addFileToProject("tests/app/MainTest.axl", "package app\n").getVirtualFile();
            String production = main.getParent().getParent().getPath();

            assertEquals(List.of(production), AxlExternalAnnotator.sourceRoots(getModule(), main));
            List<String> roots = AxlExternalAnnotator.sourceRoots(getModule(), test);
            assertTrue(roots.toString(), roots.containsAll(List.of(production, tests.getPath())));
        } finally {
            PsiTestUtil.removeSourceRoot(getModule(), tests);
        }
    }
}
