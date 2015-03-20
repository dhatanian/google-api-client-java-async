package com.github.dhatanian.googlenio;

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.util.ObjectParser;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
        com.google.api.client.http.HttpResponse httpResponse = request.executeUnparsed();
        NIOHttpTransport.WaitingForCallbackToExecuteHttpResponse waitingForCallbackToExecuteHttpResponse;
        try {
            Field f = httpResponse.getClass().getDeclaredField("response");
            f.setAccessible(true);
            waitingForCallbackToExecuteHttpResponse = (NIOHttpTransport.WaitingForCallbackToExecuteHttpResponse) f.get(httpResponse);
        } catch (NoSuchFieldException e) {
            throw new IOException("Unable to access the private response field", e);
        } catch (IllegalAccessException e) {
            throw new IOException("Unable to access the private response value", e);
        }
        waitingForCallbackToExecuteHttpResponse.withCallback(new ResponseCallback(callback, httpResponse.getRequest().getParser(), request.getResponseClass()));
    }

    private static class ResponseCallback<T> implements FutureCallback<HttpResponse> {
        private FutureCallback<T> callback;
        private ObjectParser parser;
        private Class<T> responseClass;

        public ResponseCallback(FutureCallback<T> callback, ObjectParser parser, Class<T> responseClass) {
            this.callback = callback;
            this.parser = parser;
            this.responseClass = responseClass;
        }

        @Override
        public void completed(HttpResponse result) {
            try (InputStream input = new GZIPInputStream(result.getEntity().getContent())) {
                callback.completed(parser.parseAndClose(input, ContentType.getOrDefault(result.getEntity()).getCharset(), responseClass));
            } catch (IOException e) {
                throw new RuntimeException("Unable to process response", e);
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
    }
}
