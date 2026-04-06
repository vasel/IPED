package iped.engine.webapi;

import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Documents")
@Path("/htmlcontent/supportedtypes")
public class HtmlSupportedTypes {

    @Operation(summary = "Get list of MIME types supported for HTML conversion")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public static Set<String> getSupportedTypes() {
        return HtmlContent.SUPPORTED_TYPES;
    }

    @Operation(summary = "Check if a MIME type is supported for HTML conversion")
    @GET
    @Path("{mimeType}")
    @Produces(MediaType.APPLICATION_JSON)
    public static boolean isTypeSupported(@PathParam("mimeType") String mimeType) {
        return HtmlContent.SUPPORTED_TYPES.contains(mimeType) || mimeType.startsWith("text/");
    }
}
