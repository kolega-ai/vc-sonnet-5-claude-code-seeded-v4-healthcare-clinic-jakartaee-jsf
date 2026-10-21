package com.clinic.util;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180 CSV reader/writer used by the import and export screens. */
public final class CsvSupport {

    private CsvSupport() {
    }

    /** Escapes one field for CSV output, guarding against formula injection. */
    public static String escape(String value) {
        String v = value == null ? "" : value;
        // Neutralise spreadsheet formula injection on export.
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains("\"") || v.contains(",") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    public static String row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        return sb.append('\n').toString();
    }

    /** Parses a whole CSV document into rows of fields. Blank lines are skipped. */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    fieldStarted = true;
                }
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                }
                case '\r' -> { /* ignore */ }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    if (!isBlankRow(current)) {
                        rows.add(new ArrayList<>(current));
                    }
                    current.clear();
                }
                default -> {
                    field.append(c);
                    fieldStarted = true;
                }
            }
        }
        if (fieldStarted || !field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            if (!isBlankRow(current)) {
                rows.add(new ArrayList<>(current));
            }
        }
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(s -> s == null || s.isBlank());
    }

    public static String at(List<String> row, int index) {
        if (index >= row.size()) {
            return null;
        }
        String v = row.get(index);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
