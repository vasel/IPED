package iped.engine.webapi;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;

/**
 * Factory that provides a no-op ServletContext for use with Grizzly HTTP Server
 * (which doesn't have a full Servlet container).
 */
public class NoOpServletContextFactory implements Supplier<ServletContext> {

    /** Singleton instance for direct binding */
    public static final ServletContext INSTANCE = new NoOpServletContext();

    @Override
    public ServletContext get() {
        return INSTANCE;
    }

    /**
     * Minimal ServletContext implementation that returns empty/null values.
     */
    private static class NoOpServletContext implements ServletContext {

        @Override
        public String getContextPath() {
            return "";
        }

        @Override
        public ServletContext getContext(String uripath) {
            return null;
        }

        @Override
        public int getMajorVersion() {
            return 3;
        }

        @Override
        public int getMinorVersion() {
            return 1;
        }

        @Override
        public int getEffectiveMajorVersion() {
            return 3;
        }

        @Override
        public int getEffectiveMinorVersion() {
            return 1;
        }

        @Override
        public String getMimeType(String file) {
            return null;
        }

        @Override
        public Set<String> getResourcePaths(String path) {
            return Collections.emptySet();
        }

        @Override
        public URL getResource(String path) throws MalformedURLException {
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return null;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            return null;
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public Servlet getServlet(String name) throws ServletException {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public Enumeration<Servlet> getServlets() {
            return Collections.emptyEnumeration();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Enumeration<String> getServletNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public void log(String msg) {
            // no-op
        }

        @Override
        @SuppressWarnings("deprecation")
        public void log(Exception exception, String msg) {
            // no-op
        }

        @Override
        public void log(String message, Throwable throwable) {
            // no-op
        }

        @Override
        public String getRealPath(String path) {
            return null;
        }

        @Override
        public String getServerInfo() {
            return "Grizzly HTTP Server";
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public boolean setInitParameter(String name, String value) {
            return false;
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public void setAttribute(String name, Object object) {
            // no-op
        }

        @Override
        public void removeAttribute(String name) {
            // no-op
        }

        @Override
        public String getServletContextName() {
            return "grizzly-http-server";
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, String className) {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
            return null;
        }

        @Override
        public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
            return null;
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
            return null;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName) {
            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations() {
            return Collections.emptyMap();
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, String className) {
            return null;
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
            return null;
        }

        @Override
        public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
            return null;
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName) {
            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
            return Collections.emptyMap();
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig() {
            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
            // no-op
        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
            return Collections.emptySet();
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
            return Collections.emptySet();
        }

        @Override
        public void addListener(String className) {
            // no-op
        }

        @Override
        public <T extends EventListener> void addListener(T t) {
            // no-op
        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass) {
            // no-op
        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
            return null;
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor() {
            return null;
        }

        @Override
        public ClassLoader getClassLoader() {
            return Thread.currentThread().getContextClassLoader();
        }

        @Override
        public void declareRoles(String... roleNames) {
            // no-op
        }

        @Override
        public String getVirtualServerName() {
            return "localhost";
        }

        @Override
        public int getSessionTimeout() {
            return 0;
        }

        @Override
        public void setSessionTimeout(int sessionTimeout) {
            // no-op
        }

        @Override
        public String getRequestCharacterEncoding() {
            return "UTF-8";
        }

        @Override
        public void setRequestCharacterEncoding(String encoding) {
            // no-op
        }

        @Override
        public String getResponseCharacterEncoding() {
            return "UTF-8";
        }

        @Override
        public void setResponseCharacterEncoding(String encoding) {
            // no-op
        }
    }
}
