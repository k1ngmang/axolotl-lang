package core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import axl.lang.Err;
import axl.lang.Ok;
import org.junit.jupiter.api.Test;

class TitlesTest {

    @Test
    void slugsTheTitle() {
        assertEquals(new Ok<>("hello-axolotl-world"), Titles.title(Fixtures.sample));
    }

    @Test
    void rejectsBlankTitles() {
        assertEquals(new Err<>("blank title"), Titles.title(Fixtures.blank()));
    }

    @Test
    void shortTitlePropagatesErrors() {
        assertEquals(new Ok<>("hello"), Titles.shortTitle(Fixtures.sample));
        assertEquals(new Err<>("missing title"), Titles.shortTitle(null));
    }

    @Test
    void javaSeesAxolotlResults() {
        assertEquals("error: blank title", Report.describe(Fixtures.blank()));
    }
}
