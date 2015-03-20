package com.github.dhatanian.googlenio;

import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.util.Preconditions;
import com.google.api.client.util.StreamingContent;
import org.apache.http.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.ContentEncoderChannel;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.protocol.HttpContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.Channels;

/**
 * Apache NIO implementation of Google's HTTP Transport.
 * Call {@link #shutdown() shutdown()} before closing your application is required, as this transport starts a thread.
 *
 * @see GoogleAsyncClient
 */
public class NIOHttpTransport extends HttpTransport {
    private CloseableHttpAsyncClient httpClient;

    public NIOHttpTransport() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(3000)
                .setConnectTimeout(3000).build();

        httpClient = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public NIOHttpTransport(CloseableHttpAsyncClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
        if (!httpClient.isRunning()) {
            httpClient.start();
        }
        HttpRequestBase requestBase;
        switch (method) {
            case HttpMethods.DELETE:
                requestBase = new HttpDelete(url);
                break;
            case HttpMethods.GET:
                requestBase = new HttpGet(url);
                break;
            case HttpMethods.HEAD:
                requestBase = new HttpHead(url);
                break;
            case HttpMethods.POST:
                requestBase = new HttpPost(url);
                break;
            case HttpMethods.PUT:
                requestBase = new HttpPut(url);
                break;
            case HttpMethods.TRACE:
                requestBase = new HttpTrace(url);
                break;
            case HttpMethods.OPTIONS:
                requestBase = new HttpOptions(url);
                break;
            default:
                requestBase = new HttpExtensionMethod(method, url);
                break;
        }
        return new ApacheNIOLowLevelHttpRequest(httpClient, requestBase);
    }

    /*
     * This method must be called before stopping the application, in order to shutdown the associated executor thread.
     */
    public void shutdown() throws IOException {
        httpClient.close();
    }

    /**
     * HTTP extension method.
     *
     * @author Yaniv Inbar
     */
    private final class HttpExtensionMethod extends HttpEntityEnclosingRequestBase {

        /**
         * Request method name.
         */
        private final String methodName;

        /**
         * @param methodName request method name
         * @param uri        URI
         */
        public HttpExtensionMethod(String methodName, String uri) {
            this.methodName = Preconditions.checkNotNull(methodName);
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return methodName;
        }
    }

    private class ApacheNIOLowLevelHttpRequest extends LowLevelHttpRequest {
        private final CloseableHttpAsyncClient httpclient;
        private final HttpRequestBase request;

        public ApacheNIOLowLevelHttpRequest(CloseableHttpAsyncClient httpclient, HttpRequestBase request) {
            this.httpclient = httpclient;
            this.request = request;
        }

        @Override
        public void addHeader(String name, String value) throws IOException {
            request.addHeader(name, value);
        }

        @Override
        public LowLevelHttpResponse execute() throws IOException {
            HttpUriRequest actualRequest = request;
            if (getStreamingContent() != null) {
                Preconditions.checkArgument(request instanceof HttpEntityEnclosingRequest,
                        "Apache HTTP client does not support %s requests with content.",
                        request.getRequestLine().getMethod());
                try {
                    actualRequest = (HttpUriRequest) new StreamingRequest(request, getStreamingContent(), getContentLength(), getContentType()).generateRequest();
                } catch (HttpException e) {
                    throw new IOException("Unable to generate streaming request", e);
                }
            }
            return new WaitingForCallbackToExecuteHttpResponse(httpclient, actualRequest);
        }
    }

    private class StreamingRequest implements HttpAsyncRequestProducer {
        private final HttpRequestBase request;
        private final StreamingContent streamingContent;
        private final long contentLength;
        private final String contentType;

        public StreamingRequest(HttpRequestBase request, StreamingContent streamingContent, long contentLength, String contentType) {
            this.request = request;
            this.streamingContent = streamingContent;
            this.contentLength = contentLength;
            this.contentType = contentType;
        }

        @Override
        public HttpHost getTarget() {
            return URIUtils.extractHost(request.getURI());
        }

        @Override
        public HttpRequest generateRequest() throws IOException, HttpException {
            BasicHttpEntity entity = new BasicHttpEntity();
            entity.setChunked(false);
            entity.setContentLength(contentLength);
            if (this.contentType != null) {
                entity.setContentType(this.contentType);
            }
            ((HttpEntityEnclosingRequest) request).setEntity(entity);
            return request;
        }

        @Override
        public void produceContent(ContentEncoder contentEncoder, IOControl ioControl) throws IOException {
            OutputStream outputStream = Channels.newOutputStream(new ContentEncoderChannel(contentEncoder));
            streamingContent.writeTo(outputStream);
            outputStream.flush();
            contentEncoder.complete();
        }

        @Override
        public void requestCompleted(HttpContext httpContext) {
        }

        @Override
        public void failed(Exception e) {
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public void resetRequest() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
    }

    protected class WaitingForCallbackToExecuteHttpResponse extends LowLevelHttpResponse {
        private final CloseableHttpAsyncClient httpclient;
        private final HttpUriRequest actualRequest;

        public WaitingForCallbackToExecuteHttpResponse(CloseableHttpAsyncClient httpclient, HttpUriRequest actualRequest) {
            this.httpclient = httpclient;
            this.actualRequest = actualRequest;
        }

        public void withCallback(FutureCallback<HttpResponse> callback) {
            httpclient.execute(actualRequest, callback);
        }

        @Override
        public InputStream getContent() throws IOException {
            return new ByteArrayInputStream(new byte[]{});
        }

        @Override
        public String getContentEncoding() throws IOException {
            return "DO-NOT-USE";
        }

        @Override
        public long getContentLength() throws IOException {
            throw new IOException("Non blocking response should not be called directly");
        }

        @Override
        public String getContentType() throws IOException {
            return "application/do-not-use";
        }

        @Override
        public String getStatusLine() throws IOException {
            return "-1 : ASYNC REQUEST NOT STARTED YET";
        }

        @Override
        public int getStatusCode() throws IOException {
            return 200;
        }

        @Override
        public String getReasonPhrase() throws IOException {
            return "ASYNC REQUEST NOT STARTED YET";
        }

        @Override
        public int getHeaderCount() throws IOException {
            return 0;
        }

        @Override
        public String getHeaderName(int index) throws IOException {
            throw new IOException("Non blocking response should not be called directly");
        }

        @Override
        public String getHeaderValue(int index) throws IOException {
            throw new IOException("Non blocking response should not be called directly");
        }
    }
}
