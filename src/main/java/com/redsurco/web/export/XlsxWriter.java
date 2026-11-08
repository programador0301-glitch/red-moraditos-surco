package com.redsurco.web.export;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generador de archivos Excel (.xlsx) usando solo el JDK (sin librerías externas).
 *
 * Un .xlsx es un ZIP con varios archivos XML dentro. Esta clase arma esos XML
 * con "cadenas en línea" (inlineStr) para texto y celdas numéricas para números,
 * que es suficiente para listados y resúmenes. Lo abre Excel y LibreOffice.
 */
public class XlsxWriter {

    /** Una hoja del libro: un nombre y sus filas. */
    public static class Sheet {
        private final String name;
        private final List<Object[]> rows = new ArrayList<>();

        private Sheet(String name) { this.name = name; }

        /** Agrega una fila. Cada celda: número → celda numérica; otro → texto. */
        public void addRow(Object... celdas) { rows.add(celdas); }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    public Sheet addSheet(String name) {
        Sheet s = new Sheet(nombreValido(name));
        sheets.add(s);
        return s;
    }

    /** Escribe el libro completo al archivo indicado. */
    public void write(java.io.File file) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {

            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rels());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
            }
        }
    }

    // ---- Partes del paquete XLSX ----

    private String contentTypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Override PartName=\"/xl/workbook.xml\" "
                + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1).append(".xml\" "
                    + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    private String rels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
             + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
             + "<Relationship Id=\"rId1\" "
             + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
             + "Target=\"xl/workbook.xml\"/></Relationships>";
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(esc(sheets.get(i).name)).append("\" sheetId=\"")
              .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets></workbook>");
        return sb.toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1).append("\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" "
                    + "Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private String sheetXml(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 0;
        for (Object[] fila : sheet.rows) {
            r++;
            sb.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < fila.length; c++) {
                String ref = colLetter(c) + r;
                Object v = fila[c];
                if (v instanceof Number) {
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                } else {
                    String texto = v == null ? "" : v.toString();
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is>"
                            + "<t xml:space=\"preserve\">").append(esc(texto)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    // ---- Utilidades ----

    private static void put(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        byte[] bytes = contenido.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes, 0, bytes.length);
        zip.closeEntry();
    }

    /** Letra(s) de columna a partir del índice (0 → A, 25 → Z, 26 → AA). */
    static String colLetter(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    /** Escapa caracteres especiales de XML y elimina los no válidos. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // Solo caracteres válidos en XML 1.0
                    if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20) sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    /** Un nombre de hoja válido para Excel (máx 31, sin caracteres prohibidos). */
    private static String nombreValido(String name) {
        if (name == null || name.isBlank()) return "Hoja";
        String limpio = name.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        if (limpio.length() > 31) limpio = limpio.substring(0, 31);
        return limpio.isBlank() ? "Hoja" : limpio;
    }
}
