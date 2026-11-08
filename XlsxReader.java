package com.redsurco.web.importar;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lector de archivos Excel (.xlsx) usando solo el JDK (sin librerías).
 * Un .xlsx es un ZIP con XML dentro; aquí se descomprime y se parsea la primera
 * hoja, resolviendo textos compartidos (sharedStrings), inline y números.
 */
public final class XlsxReader {

    private XlsxReader() { }

    /** Lee la primera hoja y devuelve las filas (cada fila, una lista de celdas como texto). */
    public static List<List<String>> leer(byte[] xlsx) throws Exception {
        Map<String, byte[]> files = descomprimir(xlsx);

        // Textos compartidos (opcional)
        List<String> shared = new ArrayList<>();
        if (files.containsKey("xl/sharedStrings.xml")) {
            Document d = parse(files.get("xl/sharedStrings.xml"));
            NodeList sis = d.getElementsByTagName("si");
            for (int i = 0; i < sis.getLength(); i++) {
                shared.add(textoDe((Element) sis.item(i)));
            }
        }

        // Ubicar la primera hoja (por rels; si falla, sheet1.xml)
        String hoja = primeraHoja(files);
        if (hoja == null || !files.containsKey(hoja)) {
            throw new IllegalArgumentException("El archivo no tiene una hoja de cálculo válida.");
        }
        Document doc = parse(files.get(hoja));

        List<List<String>> filas = new ArrayList<>();
        NodeList rows = doc.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            List<String> fila = new ArrayList<>();
            NodeList cells = row.getElementsByTagName("c");
            for (int j = 0; j < cells.getLength(); j++) {
                Element c = (Element) cells.item(j);
                int col = colIndex(c.getAttribute("r"));      // columna por la referencia (A1, B1…)
                while (fila.size() < col) fila.add("");        // rellena huecos
                fila.add(valorCelda(c, shared));
            }
            filas.add(fila);
        }
        return filas;
    }

    private static String valorCelda(Element c, List<String> shared) {
        String t = c.getAttribute("t");
        if ("inlineStr".equals(t)) {
            NodeList is = c.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < is.getLength(); i++) sb.append(is.item(i).getTextContent());
            return sb.toString();
        }
        NodeList vs = c.getElementsByTagName("v");
        if (vs.getLength() == 0) return "";
        String v = vs.item(0).getTextContent();
        if ("s".equals(t)) {
            try { int idx = Integer.parseInt(v.trim()); return idx >= 0 && idx < shared.size() ? shared.get(idx) : ""; }
            catch (NumberFormatException e) { return ""; }
        }
        return v; // número o string directo
    }

    /** Concatena el texto de los <t> dentro de un <si> (textos compartidos). */
    private static String textoDe(Element si) {
        NodeList ts = si.getElementsByTagName("t");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ts.getLength(); i++) sb.append(ts.item(i).getTextContent());
        return sb.toString();
    }

    /** Columna (0-based) a partir de una referencia tipo "B3" -> 1. */
    static int colIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') col = col * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') col = col * 26 + (ch - 'a' + 1);
            else break;
        }
        return Math.max(0, col - 1);
    }

    /** Encuentra el archivo de la primera hoja (vía workbook rels; fallback a sheet1.xml). */
    private static String primeraHoja(Map<String, byte[]> files) throws Exception {
        try {
            if (files.containsKey("xl/workbook.xml") && files.containsKey("xl/_rels/workbook.xml.rels")) {
                Document wb = parse(files.get("xl/workbook.xml"));
                NodeList sheets = wb.getElementsByTagName("sheet");
                if (sheets.getLength() > 0) {
                    Element s = (Element) sheets.item(0);
                    String rid = s.getAttribute("r:id");
                    Document rels = parse(files.get("xl/_rels/workbook.xml.rels"));
                    NodeList rs = rels.getElementsByTagName("Relationship");
                    for (int i = 0; i < rs.getLength(); i++) {
                        Element r = (Element) rs.item(i);
                        if (rid.equals(r.getAttribute("Id"))) {
                            String target = r.getAttribute("Target");
                            if (target.startsWith("/")) return target.substring(1);
                            return "xl/" + target.replaceFirst("^\\./", "");
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* usa fallback */ }
        if (files.containsKey("xl/worksheets/sheet1.xml")) return "xl/worksheets/sheet1.xml";
        for (String k : files.keySet()) if (k.startsWith("xl/worksheets/") && k.endsWith(".xml")) return k;
        return null;
    }

    private static Map<String, byte[]> descomprimir(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                var bos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                out.put(e.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // Seguridad: desactivar entidades externas (XXE)
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setNamespaceAware(false);
        DocumentBuilder b = f.newDocumentBuilder();
        try (InputStream in = new ByteArrayInputStream(xml)) { return b.parse(in); }
    }
}
