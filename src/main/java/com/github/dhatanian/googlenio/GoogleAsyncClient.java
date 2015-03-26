package com.github.dhatanian.googlenio;

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.http.*;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.entity.DeflateInputStream;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.zip.GZIPInputStream;

/**
 * Use this client to execute a non-blocking request asynchronously.
 * <p/>
 * Requires the {@link com.google.api.client.http.HttpTransport} implementation to be {@link NIOHttpTransport}
 * <p/>
 * Usage :
 * <pre>
 * {@code
 * Credential credential = ... //Generate an OAuth token
 * NIOHttpTransport nioTransport = new NIOHttpTransport();
 * Directory directory = new Directory.Builder(nioTransport, new JacksonFactory(), credential);
 *
 * GoogleAsyncClient.executeAsync(directory.users().list().setCustomer("my_customer"), new FutureCallback&lt;Users&gt;() {
 *   @Override
 *   public void completed(Users result) {
 *      try {
 *          System.out.println("Completed, result : " + result.toPrettyString());
 *      } catch (IOException e) {
 *          e.printStackTrace();
 *      }
 *      done[0] = true;
 *    }
 *    @Override
 *    public void failed(Exception ex) {
 *      System.err.println("Failed");
 *      ex.printStackTrace();
 *      done[0] = true;
 *    }
 *    @Override
 *    public void cancelled() {
 *      System.err.println("Cancelled");
 *      done[0] = true;
 *    }
 * });
 *
 * while (!done[0]) {
 *   Thread.sleep(100L);
 * }
 * nioHttpTransport.shutdown();
 * }
 * </pre>
 */
public class GoogleAsyncClient {
    public static <T> void executeAsync(AbstractGoogleClientRequest<T> request, FutureCallback<T> callback) throws IOException {
        executeAsync(request, callback, false);
    }

    private static class ResponseCallback<T> implements FutureCallback<HttpResponse> {
        private FutureCallback<T> callback;
        private HttpRequest request;
        private Class<T> responseClass;
        private AbstractGoogleClientRequest<T> originalRequest;
        private boolean isRetry;

        public ResponseCallback(FutureCallback<T> callback, HttpRequest request, Class<T> responseClass, AbstractGoogleClientRequest<T> originalRequest, boolean isRetry) {
            this.callback = callback;
            this.request = request;
            this.responseClass = responseClass;
            this.originalRequest = originalRequest;
            this.isRetry = isRetry;
        }

        @Override
        public void completed(HttpResponse result) {
            LowLevelHttpResponseProxy lowLevelHttpResponseProxy = new LowLevelHttpResponseProxy(result);
            com.google.api.client.http.HttpResponse googleHttpResponse = buildGoogleHttpResponse(request, lowLevelHttpResponseProxy);
            if (!HttpStatusCodes.isSuccess(result.getStatusLine().getStatusCode())) {
                StringBuilder logger = new StringBuilder();
                HttpHeaders httpHeaders = new HttpHeaders();
                try {
                    httpHeaders.fromHttpResponse(lowLevelHttpResponseProxy, logger);
                } catch (IOException e) {
                    throw new RuntimeException("Unable to read headers", e);
                }

                boolean errorHandled = false;
                if (request.getUnsuccessfulResponseHandler() != null) {
                    // Even if we don't have the potential to retry, we might want to run the
                    // handler to fix conditions (like expired tokens) that might cause us
                    // trouble on our next request
                    try {
                        errorHandled = request.getUnsuccessfulResponseHandler().handleResponse(request, googleHttpResponse, true);
                    } catch (IOException e1) {
                        throw new RuntimeException("Unable to handle error in request", e1);
                    }
                }
                if (!errorHandled) {
                    if (request.handleRedirect(result.getStatusLine().getStatusCode(), httpHeaders)) {
                        // The unsuccessful request's error could not be handled and it is a redirect request.
                        errorHandled = true;
                    }
                }
                // A retry is required if the error was successfully handled or if it is a redirect
                if (errorHandled) {
                    if (!isRetry) {
                        try {
                            result.getEntity().getContent().close();
                            GoogleAsyncClient.executeAsync(originalRequest, callback, true);
                            return;
                        } catch (IOException e1) {
                            throw new RuntimeException("Unable to retry request", e1);
                        }
                    } else {
                        callback.failed(new HttpResponseException(googleHttpResponse));
                    }
                }
            }

            // throw an exception if unsuccessful response
            if (!HttpStatusCodes.isSuccess(result.getStatusLine().getStatusCode())) {
                callback.failed(new HttpResponseException(googleHttpResponse));
            }

            try (InputStream input = contentFromEncodingInputStream(result.getEntity().getContent(), result.getEntity().getContentType())) {
                callback.completed(request.getParser().parseAndClose(input, ContentType.getOrDefault(result.getEntity()).getCharset(), responseClass));
            } catch (IOException e) {
                throw new RuntimeException("Unable to process response", e);
            }
        }

        private InputStream contentFromEncodingInputStream(InputStream content, Header contentType) throws IOException {
            if (contentType == null || contentType.getValue() == null) {
                return content;
            }
            switch (contentType.getValue().toLowerCase()) {
                case "gzip":
                    return new GZIPInputStream(content);
                case "deflate":
                    return new DeflateInputStream(content);
                default:
                    return content;
            }
        }

        private com.google.api.client.http.HttpResponse buildGoogleHttpResponse(HttpRequest request, LowLevelHttpResponse lowLevelHttpResponse) {
            Constructor<?> constructor;
            try {
                constructor = com.google.api.client.http.HttpResponse.class.getDeclaredConstructor(HttpRequest.class, LowLevelHttpResponse.class);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Expecting a constructor for HttpResponse, check your version of the API client", e);
            }
            constructor.setAccessible(true);
            try {
                return (com.google.api.client.http.HttpResponse) constructor.newInstance(request, lowLevelHttpResponse);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException("Unable to build the HttpResponse", e);
            }
        }

        @Override
        public void failed(Exception ex) {
            callback.failed(ex);
        }

        @Override
        public void cancelled() {
            callback.cancelled();
        }

        private class LowLevelHttpResponseProxy extends LowLevelHttpResponse {
            private HttpResponse response;

            public LowLevelHttpResponseProxy(HttpResponse response) {
                this.response = response;
            }

            @Override
            public InputStream getContent() throws IOException {
                if (response.getEntity() == null) {
                    return null;
                }
                return response.getEntity().getContent();
            }

            @Override
            public String getContentEncoding() throws IOException {
                if (response.getEntity() == null || response.getEntity().getContentEncoding() == null) {
                    return null;
                }
                return response.getEntity().getContentEncoding().getValue();
            }

            @Override
            public long getContentLength() throws IOException {
                if (response.getEntity() == null) {
                    return 0;
                }
                return response.getEntity().getContentLength();
            }

            @Override
            public String getContentType() throws IOException {
                if (response.getEntity() == null || response.getEntity().getContentType() == null) {
                    return null;
                }
                return response.getEntity().getContentType().getValue();
            }

            @Override
            public String getStatusLine() throws IOException {
                if (response.getStatusLine() == null) {
                    return null;
                }
                return response.getStatusLine().getProtocolVersion() + " " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase();
            }

            @Override
            public int getStatusCode() throws IOException {
                if (response.getStatusLine() == null) {
                    return -1;
                }
                return response.getStatusLine().getStatusCode();
            }

            @Override
            public String getReasonPhrase() throws IOException {
                if (response.getStatusLine() == null) {
                    return null;
                }
                return response.getStatusLine().getReasonPhrase();
            }

            @Override
            public int getHeaderCount() throws IOException {
                return response.getAllHeaders().length;
            }

            @Override
            public String getHeaderName(int index) throws IOException {
                return response.getAllHeaders()[index].getName();
            }

            @Override
            public String getHeaderValue(int index) throws IOException {
                return response.getAllHeaders()[index].getValue();
            }
        }
    }

    private static <T> void executeAsync(AbstractGoogleClientRequest<T> request, FutureCallback<T> callback, boolean isRetry) throws IOException {
        com.google.api.client.http.HttpResponse httpResponse = request.executeUnparsed();
        NIOHttpTransport.WaitingForCallbackToExecuteHttpResponse waitingForCallbackToExecuteHttpResponse;
        try {
            Field f = httpResponse.getClass().getDeclaredField("response");
            f.setAccessible(true);
            if (httpResponse.getRequest().getInterceptor() != null) {
                httpResponse.getRequest().getInterceptor().intercept(httpResponse.getRequest());
            }
            waitingForCallbackToExecuteHttpResponse = (NIOHttpTransport.WaitingForCallbackToExecuteHttpResponse) f.get(httpResponse);
        } catch (NoSuchFieldException e) {
            throw new IOException("Unable to access the private response field", e);
        } catch (IllegalAccessException e) {
            throw new IOException("Unable to access the private response value", e);
        }
        waitingForCallbackToExecuteHttpResponse.withCallback(new ResponseCallback(callback, httpResponse.getRequest(), request.getResponseClass(), request, isRetry));
    }
}
