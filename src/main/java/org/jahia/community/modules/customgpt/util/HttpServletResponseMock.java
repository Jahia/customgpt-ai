package org.jahia.community.modules.customgpt.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;


/**
 * Minimal {@link HttpServletResponse} stub used together with {@link HttpServletRequestMock} to build a
 * {@link org.jahia.services.render.RenderContext} for programmatic page rendering during indexation.
 */
public class HttpServletResponseMock implements HttpServletResponse {
    private final StringWriter out;

    @Override
    public int getStatus() {
        return 0;
    }

    @Override
    public String getHeader(String s) {
        return null;
    }

    @Override
    public Collection<String> getHeaders(String s) {
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.emptyList();
    }

    public HttpServletResponseMock(StringWriter out) {
        this.out = out;
    }

    @Override
    public void addCookie(Cookie cookie) {
        // no-op for test mock
    }

    @Override
    public boolean containsHeader(String name) {
        return false;
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return null;
    }

    @Override
    public String encodeUrl(String url) {
        return null;
    }

    @Override
    public String encodeRedirectUrl(String url) {
        return null;
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        // no-op for test mock
    }

    @Override
    public void sendError(int sc) throws IOException {
        // no-op for test mock
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        // no-op for test mock
    }

    @Override
    public void setDateHeader(String name, long date) {
        // no-op for test mock
    }

    @Override
    public void addDateHeader(String name, long date) {
        // no-op for test mock
    }

    @Override
    public void setHeader(String name, String value) {
        // no-op for test mock
    }

    @Override
    public void addHeader(String name, String value) {
        // no-op for test mock
    }

    @Override
    public void setIntHeader(String name, int value) {
        // no-op for test mock
    }

    @Override
    public void addIntHeader(String name, int value) {
        // no-op for test mock
    }

    @Override
    public void setStatus(int sc) {
        // no-op for test mock
    }

    @Override
    public void setStatus(int sc, String sm) {
        // no-op for test mock
    }

    @Override
    public String getCharacterEncoding() {
        return null;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        // no-op for test mock
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public void setContentType(String type) {
        // no-op for test mock
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return null;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return new PrintWriter(out);
    }

    @Override
    public void setContentLength(int len) {
        // no-op for test mock
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void setBufferSize(int size) {
        // no-op for test mock
    }

    @Override
    public void flushBuffer() throws IOException {
        // no-op for test mock
    }

    @Override
    public void resetBuffer() {
        // no-op for test mock
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {
        // no-op for test mock
    }

    @Override
    public Locale getLocale() {
        return null;
    }

    @Override
    public void setLocale(Locale loc) {
        // no-op for test mock
    }

    @Override
    public void setContentLengthLong(long arg0) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}