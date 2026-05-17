package org.axl.intellij.annotate;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class AxlExternalAnnotatorTest extends LightJavaCodeInsightFixtureTestCase {

    public void testRunsTheCheckOnceForTheFilesOfAModuleUntilAFileChanges() {
        AtomicInteger runs = new AtomicInteger();
        Function<List<String>, List<String>> runner = command -> List.of("run " + runs.incrementAndGet());
        List<String> command = List.of("axl", "check", getTestName(false));
        var first = new AxlExternalAnnotator.Request(getModule(), "/src/A.axl", command, 1);
        var second = new AxlExternalAnnotator.Request(getModule(), "/src/B.axl", command, 1);
        var changed = new AxlExternalAnnotator.Request(getModule(), "/src/A.axl", command, 2);

        assertEquals(List.of("run 1"), AxlExternalAnnotator.output(first, runner));
        assertEquals(List.of("run 1"), AxlExternalAnnotator.output(second, runner));
        assertEquals(List.of("run 2"), AxlExternalAnnotator.output(changed, runner));
    }

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
