package org.axl.intellij.lsp;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AxlLspModulesTest extends LightJavaCodeInsightFixtureTestCase {

    public void testDescribesTheProductionAndTestSourcesOfAModule() throws IOException {
        VirtualFile main = myFixture.addFileToProject("app/Main.axl", "package app\n").getVirtualFile();
        String production = main.getParent().getParent().getPath();
        VirtualFile tests = myFixture.getTempDirFixture().findOrCreateDir("tests");
        PsiTestUtil.addSourceRoot(getModule(), tests, true);
        try {
            List<Map<String, Object>> modules = AxlLspModules.of(getProject());

            assertEquals(2, modules.size());
            assertEquals(List.of(production), modules.get(0).get("sourceRoots"));
            assertEquals(List.of(tests.getPath(), production), modules.get(1).get("sourceRoots"));
            assertEquals(List.of(tests.getPath()), modules.get(1).get("javaSourceRoots"));
            assertEquals(List.of(production), modules.get(1).get("javaSourcepath"));
            assertNotNull(modules.get(0).get("root"));
        } finally {
            PsiTestUtil.removeSourceRoot(getModule(), tests);
        }
    }

    public void testTellsWhetherTheModulesChangedSinceTheServerStarted() throws IOException {
        myFixture.addFileToProject("app/Main.axl", "package app\n");
        AxlLspModules.forServer(getProject());
        assertFalse(AxlLspModules.changed(getProject()));
        VirtualFile tests = myFixture.getTempDirFixture().findOrCreateDir("tests");
        PsiTestUtil.addSourceRoot(getModule(), tests, true);
        try {
            assertTrue(AxlLspModules.changed(getProject()));
        } finally {
            PsiTestUtil.removeSourceRoot(getModule(), tests);
        }
    }
}
