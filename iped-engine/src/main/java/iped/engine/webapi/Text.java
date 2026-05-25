package iped.engine.webapi;

import java.io.IOException;
import java.io.OutputStream;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToTextContentHandler;
import org.xml.sax.ContentHandler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.task.ParsingTask;
import iped.engine.task.index.IndexItem;
import iped.parsers.standard.StandardParser;

@Tag(name = "Documents")
@Path("/sources/{sourceID}/docs/{id}/text")
public class Text {

    @Operation(summary = "Get document's content converted as text")
    @GET
    @Produces(MediaType.TEXT_PLAIN + "; charset=UTF-8")
    public static StreamingOutput content(@PathParam("sourceID") String sourceID, @PathParam("id") int id)
            throws Exception {

        // Phase tracking for request instrumentation
        RequestTracker.RequestInfo reqInfo = null;
        Long reqId = RequestTracker.getCurrentRequestId();
        if (reqId != null) {
            reqInfo = RequestTracker.getInstance().getRequest(reqId);
        }

        if (reqInfo != null) reqInfo.markPhase("resolve_source");
        IIPEDSource source = Sources.getSource(sourceID);

        // Use lightweight loader: loads only ~15 fields instead of 50+
        if (reqInfo != null) reqInfo.markPhase("load_item");
        int luceneId = source.getLuceneId(id);
        final IItem item = IndexItem.getItemForStreaming((IPEDSource) source, luceneId);

        if (reqInfo != null) reqInfo.markPhase("setup_parser");
        final StandardParser parser = new StandardParser();
        final ParseContext context = getTikaContext(item, parser, (IPEDSource) source);
        final Metadata metadata = new Metadata();

        ParsingTask.fillMetadata(item, metadata);
        parser.setPrintMetadata(false);

        if (reqInfo != null) reqInfo.markPhase("build_response");

        return new StreamingOutput() {
            @Override
            public void write(OutputStream arg0) throws IOException, WebApplicationException {
                ContentHandler handler = new ToTextContentHandler(arg0, "UTF-8");
                try (TikaInputStream is = item.getTikaStream()) {
                    parser.parse(is, handler, metadata, context);
                } catch (Exception e) {
                    throw new WebApplicationException(e);
                }
            }
        };
    }

    public static ParseContext getTikaContext(IItem item, Parser parser, IPEDSource source) throws Exception {
        ParsingTask expander = new ParsingTask(item, (StandardParser) parser);
        expander.init(ConfigurationManager.get());
        ParseContext context = expander.getTikaContext(source);
        expander.setExtractEmbedded(false);
        return context;
    }

}
