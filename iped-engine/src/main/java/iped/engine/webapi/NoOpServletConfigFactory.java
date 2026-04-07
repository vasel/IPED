package iped.engine.webapi;

import java.util.Collections;
import java.util.Enumeration;
import java.util.function.Supplier;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

/**
 * Factory that provides a no-op ServletConfig for use with Grizzly HTTP Server
 * (which doesn't have a full Servlet container).
 */
public class NoOpServletConfigFactory implements Supplier<ServletConfig> {

    /** Singleton instance for direct binding */
    public static final ServletConfig INSTANCE = new NoOpServletConfig();

    @Override
    public ServletConfig get() {
        return INSTANCE;
    }

    /**
     * Minimal ServletConfig implementation that returns empty/null values.
     */
    private static class NoOpServletConfig implements ServletConfig {

        @Override
        public String getServletName() {
            return "grizzly-http-server";
        }

        @Override
        public ServletContext getServletContext() {
            return NoOpServletContextFactory.INSTANCE;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}
