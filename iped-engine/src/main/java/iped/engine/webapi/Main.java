package iped.engine.webapi;

import java.io.IOException;
import java.net.URI;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.json.simple.parser.ParseException;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import iped.engine.Version;

/**
 * Main class.
 *
 */
public class Main {

    static volatile HttpServer httpServer;

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
     * application.
     * 
     * @return Grizzly HTTP server.
     * @throws IOException
     * @throws ParseException
     */
    public static HttpServer startServer(String host, int port, String urlToAskSources, boolean enableGraph,
            boolean checkSources) throws Exception {
        System.out.println("Configuring JAX-RS resources...");
        // create a resource config that scans for JAX-RS resources and providers
        // in gpinf.api package
        String resources = Main.class.getPackageName();

        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("IPED WebAPI")
                        .version(Version.APP_VERSION)
                        .description("IPED - Digital Evidence Indexer and Processor REST API"));
        SwaggerConfiguration oasConfig = new SwaggerConfiguration()
                .openAPI(openAPI)
                .resourcePackages(java.util.Collections.singleton(resources))
                .prettyPrint(true);

        final ResourceConfig rc = new ResourceConfig().packages(resources)
                .register(OpenApiResource.class)
                .register(ConnectionClosedExceptionMapper.class)
                .register(RequestTrackingFilter.class)
                .register(new ServletConfigBinder());

        // Pass the OpenAPI configuration to the resource
        rc.property("openApi.configuration", oasConfig);

        System.out.println("Initializing sources...");
        long initStart = System.currentTimeMillis();
        Sources.init(urlToAskSources, checkSources);
        long initElapsed = System.currentTimeMillis() - initStart;
        System.out.println("Sources initialized in " + initElapsed + "ms.");

        // Initialize graph service if enabled
        if (enableGraph) {
            try {
                MultiCaseGraphLoader.init();
                System.out.println("Graph API enabled");
            } catch (Exception e) {
                System.err.println("Failed to initialize graph: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // https://stackoverflow.com/questions/26546373/showing-grizzly-exceptions-in-eclipse-console
        Logger l = Logger.getLogger("org.glassfish.grizzly.http.server.HttpHandler");
        l.setLevel(Level.FINE);
        l.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        l.addHandler(ch);

        // create and start a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        System.out.println("Starting HTTP server on " + host + ":" + port + "...");
        return GrizzlyHttpServerFactory.createHttpServer(URI.create("http://" + host + ":" + port), rc);
    }

    /**
     * Main method.
     * 
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = 8080;
        String urlToAskSources = null;
        boolean enableGraph = false;
        boolean checkSources = false;

        for (String arg : args) {
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());

            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));

            } else if (arg.startsWith("--sources=")) {
                urlToAskSources = arg.substring("--sources=".length());

            } else if (arg.equals("--enable-graph")) {
                enableGraph = true;

            } else if (arg.equals("--check-sources")) {
                checkSources = true;

            } else {
                printHelp();
                System.exit(-1);
            }
        }
        if (urlToAskSources == null) {
            System.err.println("missing --sources option");
            printHelp();
            System.exit(-1);
        }
        httpServer = startServer(host, port, urlToAskSources, enableGraph, checkSources);
        System.out.println(String.format("Jersey app started with WADL available at \n%sapplication.wadl\n",
                "http://" + host + ":" + port + "/"));
        
        // Start the interactive request monitor console
        RequestMonitorConsole console = new RequestMonitorConsole();
        Thread consoleThread = new Thread(console, "RequestMonitorConsole");
        consoleThread.setDaemon(true);
        consoleThread.start();
        
        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            console.stop();
        }
    }

    public static void printHelp() {
        System.out.println("--sources=(URL|Path)\tfile or url with json: [{id, path}...]");
        System.out.println("--host=\t\tdefault:0.0.0.0");
        System.out.println("--port=\t\tdefault:8080");
        System.out.println("--enable-graph\t\tenable graph API endpoints");
        System.out.println("--check-sources\t\tverify data source files exist on startup");
    }
}
