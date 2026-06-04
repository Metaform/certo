package org.metaform.certo.common.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a minimal, self-contained single-page PDF document so the demo can serve a real
 * {@code application/pdf} body for the certificate binary. Not a general-purpose PDF library — it
 * emits just enough valid PDF structure (catalog, pages, one page, Helvetica font, a text stream
 * with correct cross-reference offsets) to render a few lines of text.
 */
public final class PdfGenerator {

    private PdfGenerator() {
    }

    /**
     * Builds a one-page PDF rendering the given lines of text.
     *
     * @param title the document title, drawn larger at the top
     * @param lines body lines drawn beneath the title
     * @return the encoded PDF bytes
     */
    public static byte[] generate(String title, List<String> lines) {
        // Build the page content stream (text drawing operators).
        var content = new StringBuilder();
        content.append("BT\n");
        content.append("/F1 20 Tf\n");
        content.append("72 720 Td\n");
        content.append("(").append(escape(title)).append(") Tj\n");
        content.append("/F1 12 Tf\n");
        for (var line : lines) {
            // Move down 28 units, then draw the line.
            content.append("0 -28 Td\n");
            content.append("(").append(escape(line)).append(") Tj\n");
        }
        content.append("ET");
        var contentBytes = content.toString().getBytes(StandardCharsets.US_ASCII);

        var objects = new ArrayList<String>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objects.add("<< /Length " + contentBytes.length + " >>\nstream\n"
                + content + "\nendstream");

        var out = new ByteArrayOutputStream();
        var offsets = new ArrayList<Integer>();
        write(out, "%PDF-1.4\n");
        // Binary comment marker so tools treat the file as binary.
        write(out, "%âãÏÓ\n");

        for (var i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }

        var xrefOffset = out.size();
        var count = objects.size() + 1; // include the free object 0
        var xref = new StringBuilder();
        xref.append("xref\n");
        xref.append("0 ").append(count).append("\n");
        xref.append("0000000000 65535 f \n");
        for (var offset : offsets) {
            xref.append(String.format("%010d 00000 n \n", offset));
        }
        xref.append("trailer\n");
        xref.append("<< /Size ").append(count).append(" /Root 1 0 R >>\n");
        xref.append("startxref\n");
        xref.append(xrefOffset).append("\n");
        xref.append("%%EOF");
        write(out, xref.toString());

        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        var bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
