package io.github.dglearner.opsagent.ingest.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body size, including chunked requests that have no Content-Length. Reading past the limit throws
 * {@link PayloadTooLargeException}; the JSON converter wraps it and {@link ApiExceptionHandler} answers 413.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long limitBytes;

    public RequestSizeLimitFilter(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new LimitedRequest(request, limitBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limitBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limitBytes, getContentLengthLong());
            }
            return stream;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private final long declaredLength;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long limitBytes, long declaredLength) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
            this.declaredLength = declaredLength;
        }

        @Override
        public int read() throws IOException {
            checkDeclaredLength();
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkDeclaredLength();
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void checkDeclaredLength() throws PayloadTooLargeException {
            if (declaredLength > limitBytes) {
                throw new PayloadTooLargeException(limitBytes);
            }
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > limitBytes) {
                throw new PayloadTooLargeException(limitBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
