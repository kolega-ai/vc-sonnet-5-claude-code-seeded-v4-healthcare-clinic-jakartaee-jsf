package com.clinic.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvSupportTest {

    @Test
    @DisplayName("export escaping neutralises spreadsheet formula injection")
    void escapesFormulas() {
        assertEquals("'=cmd|' /c calc'!A1", CsvSupport.escape("=cmd|' /c calc'!A1")
                .replace("\"", ""));
        assertEquals("'+1", CsvSupport.escape("+1"));
        assertEquals("'-1", CsvSupport.escape("-1"));
        assertEquals("'@x", CsvSupport.escape("@x"));
        assertEquals("plain", CsvSupport.escape("plain"));
    }

    @Test
    void quotesSeparatorsAndQuotes() {
        assertEquals("\"a,b\"", CsvSupport.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvSupport.escape("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvSupport.escape("line1\nline2"));
    }

    @Test
    void rowsAreNewlineTerminated() {
        assertEquals("a,b,c\n", CsvSupport.row("a", "b", "c"));
        assertEquals("a,,c\n", CsvSupport.row("a", null, "c"));
    }

    @Test
    void parsesQuotedFieldsAndSkipsBlankLines() {
        List<List<String>> rows = CsvSupport.parse(
                "first_name,last_name,note\n"
                        + "Ada,Whitfield,\"has, a comma\"\n"
                        + "\n"
                        + "Bruno,Castellanos,\"said \"\"hello\"\"\"\n");
        assertEquals(3, rows.size());
        assertEquals(List.of("first_name", "last_name", "note"), rows.get(0));
        assertEquals("has, a comma", rows.get(1).get(2));
        assertEquals("said \"hello\"", rows.get(2).get(2));
    }

    @Test
    void atReturnsNullForMissingOrBlankCells() {
        List<String> row = List.of("Ada", "  ", "Whitfield");
        assertEquals("Ada", CsvSupport.at(row, 0));
        assertNull(CsvSupport.at(row, 1));
        assertNull(CsvSupport.at(row, 9));
    }
}
