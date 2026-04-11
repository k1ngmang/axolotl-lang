package org.axl.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRepositoryTest {

    @TempDir
    Path home;

    private void settings(String body) throws Exception {
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"), "<?xml version=\"1.0\"?>\n"
                + "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\">\n" + body + "\n</settings>\n");
    }

    @Test
    void defaultsToTheRepositoryInTheHomeDirectory() {
        assertEquals(home.resolve(".m2/repository"), LocalRepository.find(home, null, null));
    }

    @Test
    void takesTheLocalRepositoryOfTheSettings() throws Exception {
        settings("  <localRepository>${user.home}/maven/repo</localRepository>");
        assertEquals(Path.of(home + "/maven/repo"), LocalRepository.find(home, null, null));
    }

    @Test
    void ignoresALocalRepositoryInAComment() throws Exception {
        settings("  <!-- <localRepository>/path/to/local/repo</localRepository> -->");
        assertEquals(home.resolve(".m2/repository"), LocalRepository.find(home, null, null));
    }

    @Test
    void prefersThePropertyToTheSettings() throws Exception {
        settings("  <localRepository>/from/settings</localRepository>");
        assertEquals(Path.of("/from/property"), LocalRepository.find(home, "/from/property", "-Dmaven.repo.local=/from/opts"));
        assertEquals(Path.of("/from/opts"), LocalRepository.find(home, null, "-Xmx1g -Dmaven.repo.local=/from/opts"));
    }

    @Test
    void fallsBackToTheDefaultWhenTheSettingsAreBroken() throws Exception {
        settings("  <localRepository>/unclosed");
        assertEquals(home.resolve(".m2/repository"), LocalRepository.find(home, null, null));
    }
}
