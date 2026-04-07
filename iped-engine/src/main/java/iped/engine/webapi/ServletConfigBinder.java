package iped.engine.webapi;

import javax.inject.Singleton;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import org.glassfish.jersey.internal.inject.AbstractBinder;

/**
 * Binder to provide Servlet API types that are not available in standalone
 * Grizzly HTTP Server. This allows Swagger to work without a full Servlet
 * container.
 */
public class ServletConfigBinder extends AbstractBinder {

    @Override
    protected void configure() {
        // Bind singleton instances directly to avoid HK2 trying to instantiate the interfaces
        bind(NoOpServletConfigFactory.INSTANCE)
                .to(ServletConfig.class)
                .ranked(Integer.MAX_VALUE);
        
        bind(NoOpServletContextFactory.INSTANCE)
                .to(ServletContext.class)
                .ranked(Integer.MAX_VALUE);
    }
}
