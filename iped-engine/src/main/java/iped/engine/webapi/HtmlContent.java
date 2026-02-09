package iped.engine.webapi;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.engine.webapi.Text;
import iped.engine.task.ParsingTask;
import iped.engine.data.IPEDSource;
import iped.parsers.standard.StandardParser;
import iped.parsers.util.ToXMLContentHandler;

@Api(value = "Documents")
@Path("/sources/{sourceID}/docs/{id}/htmlcontent")
public class HtmlContent {

    /**
     * Lista de tipos MIME que são bem suportados para conversão HTML.
     * Inclui tipos que o Tika consegue parsear e produzir output estruturado.
     */
    public static final Set<String> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            // HTML/XML nativos
            "text/html",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",

            // Email
            "message/rfc822",
            "application/mbox",
            "application/vnd.ms-outlook",

            // Texto
            "text/plain",
            "text/csv",
            "text/rtf",

            // Documentos Office
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",

            // OpenDocument
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",

            // PDF
            "application/pdf",

            // RTF
            "application/rtf",

            // NFe/CTe
            "application/x-nfe+xml",
            "application/x-cte+xml"));

    @ApiOperation(value = "Get document's content converted as HTML")
    @GET
    @Produces(MediaType.TEXT_HTML + "; charset=UTF-8")
    public static StreamingOutput htmlContent(@PathParam("sourceID") String sourceID, @PathParam("id") int id) {
        return new StreamingOutput() {
            @Override
            public void write(OutputStream output) throws IOException, WebApplicationException {
                try {
                    IIPEDSource source = Sources.getSource(sourceID);
                    final IItem item = source.getItemByID(id);

                    // Verifica suporte a NFe/CTe
                    iped.viewers.NfeViewer nfeViewer = new iped.viewers.NfeViewer();
                    String mediaType = item.getMediaType().toString();
                    if (nfeViewer.isSupportedType(mediaType)) {
                        java.io.File tempHtml = java.io.File.createTempFile("nfe_viewer", ".html");
                        try {
                            nfeViewer.createNfeHtml(item, tempHtml);
                            try (java.io.InputStream is = java.nio.file.Files.newInputStream(tempHtml.toPath())) {
                                org.apache.commons.io.IOUtils.copy(is, output);
                            }
                        } finally {
                            if (tempHtml.exists()) {
                                tempHtml.delete();
                            }
                        }
                        return;
                    }

                    final StandardParser parser = new StandardParser();
                    final ParseContext context = Text.getTikaContext(item, parser, (IPEDSource) source);
                    final Metadata metadata = new Metadata();

                    ParsingTask.fillMetadata(item, metadata);
                    parser.setPrintMetadata(false);

                    ContentHandler handler = new ToXMLContentHandler(output, "UTF-8");

                    try (TikaInputStream is = item.getTikaStream()) {
                        parser.parse(is, handler, metadata, context);
                    }

                } catch (Exception e) {
                    // Em caso de erro, retorna HTML com mensagem de erro
                    writeErrorHtml(output, e);
                }
            }
        };
    }

    /**
     * Gera uma página HTML com mensagem de erro amigável.
     */
    private static void writeErrorHtml(OutputStream output, Exception e) throws IOException {
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writer.write("<!DOCTYPE html>\n");
        writer.write("<html>\n<head>\n");
        writer.write("<meta charset=\"UTF-8\">\n");
        writer.write("<title>Erro ao processar documento</title>\n");
        writer.write("<style>\n");
        writer.write("body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }\n");
        writer.write(
                ".error-box { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 20px; max-width: 600px; }\n");
        writer.write(".error-title { color: #d32f2f; margin: 0 0 10px 0; }\n");
        writer.write(
                ".error-message { color: #666; background: #f9f9f9; padding: 10px; border-radius: 4px; font-family: monospace; font-size: 12px; overflow-x: auto; }\n");
        writer.write("</style>\n");
        writer.write("</head>\n<body>\n");
        writer.write("<div class=\"error-box\">\n");
        writer.write("<h2 class=\"error-title\">&#9888; Erro ao processar documento</h2>\n");
        writer.write("<p>Não foi possível converter o conteúdo para HTML.</p>\n");
        writer.write("<div class=\"error-message\">");
        writer.write(escapeHtml(e.getClass().getSimpleName() + ": " + e.getMessage()));
        writer.write("</div>\n");
        writer.write("</div>\n");
        writer.write("</body>\n</html>");
        writer.flush();
    }

    /**
     * Escapa caracteres especiais HTML para evitar XSS.
     */
    private static String escapeHtml(String text) {
        if (text == null) {
            return "Erro desconhecido";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
