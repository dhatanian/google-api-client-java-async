package com.github.dhatanian.googlenio;

import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.admin.directory.Directory;
import com.google.api.services.admin.directory.model.Users;
import org.apache.http.concurrent.FutureCallback;
import org.bigtesting.fixd.Method;
import org.bigtesting.fixd.ServerFixture;
import org.bigtesting.fixd.request.HttpRequest;
import org.bigtesting.fixd.request.HttpRequestHandler;
import org.bigtesting.fixd.response.HttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GoogleAsyncClientTest {
    private static final int PORT = 6666;
    private ServerFixture server;
    private NIOHttpTransport nioHttpTransport = new NIOHttpTransport();
    private Directory directory = new Directory.Builder(nioHttpTransport, new JacksonFactory(), new FakeGoogleCredential()).setRootUrl("http://localhost:" + PORT).setServicePath("/").setApplicationName("test").build();
    private final Boolean[] done = {false};

    @Before
    public void beforeEachTest() throws Exception {
        server = new ServerFixture(PORT);
        server.start();
        done[0] = false;
    }

    @After
    public void stopServer() throws IOException {
        server.stop();
        nioHttpTransport.shutdown();
    }

    @Test
    public void shouldFetchResponseFromServer() throws Exception {
        server.handle(Method.GET, "/users")
                .with(200, "application/json", "{\n" +
                        "  \"kind\" : \"admin#directory#users\",\n" +
                        "  \"users\" : [ {\n" +
                        "    \"agreedToTerms\" : true,\n" +
                        "    \"aliases\" : [ \"anouche@revevolcloud.com\", \"anouchebis@revevolcloud.com\", \"annie@revevolcloud.com\" ],\n" +
                        "    \"changePasswordAtNextLogin\" : false,\n" +
                        "    \"creationTime\" : \"2013-09-10T15:08:00.000Z\",\n" +
                        "    \"customerId\" : \"C02g0ie33\",\n" +
                        "    \"emails\" : [ {\n" +
                        "      \"address\" : \"anouche.sariand@revevolcloud.com\",\n" +
                        "      \"primary\" : true\n" +
                        "    } ],\n" +
                        "    \"id\" : \"100671467136357495231\",\n" +
                        "    \"includeInGlobalAddressList\" : true,\n" +
                        "    \"ipWhitelisted\" : false,\n" +
                        "    \"isAdmin\" : true,\n" +
                        "    \"isDelegatedAdmin\" : false,\n" +
                        "    \"isMailboxSetup\" : true,\n" +
                        "    \"kind\" : \"admin#directory#user\",\n" +
                        "    \"lastLoginTime\" : \"2015-03-23T10:14:16.000Z\",\n" +
                        "    \"name\" : {\n" +
                        "      \"familyName\" : \"Sariand\",\n" +
                        "      \"fullName\" : \"Anouche Sariand\",\n" +
                        "      \"givenName\" : \"Anouche\"\n" +
                        "    },\n" +
                        "    \"orgUnitPath\" : \"/\",\n" +
                        "    \"primaryEmail\" : \"anouche.sariand@revevolcloud.com\",\n" +
                        "    \"suspended\" : false,\n" +
                        "    \"etag\" : \"\\\"0As_BCEXgB6xS4FDN2QngtrqYl0/dQrWwNm_aGMLGF1jIpiWnC407e8\\\"\"\n" +
                        "  }]}");

        final Users successResult = executeHttpRequestToGetUsers();

        assertEquals(1, successResult.getUsers().size());
    }

    @Test
    public void shouldRetryInCaseOf403Error() throws Exception {
        final int[] attemptCounts = {0};
        server.handle(Method.GET, "/users")
                .with(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest httpRequest, HttpResponse httpResponse) {
                        attemptCounts[0]++;
                        if (attemptCounts[0] == 1) {
                            httpResponse.setStatusCode(403);
                        }else {
                            httpResponse.setStatusCode(200);
                            httpResponse.setBody("{\n" +
                                    "  \"kind\" : \"admin#directory#users\",\n" +
                                    "  \"users\" : [ {\n" +
                                    "    \"agreedToTerms\" : true,\n" +
                                    "    \"aliases\" : [ \"anouche@revevolcloud.com\", \"anouchebis@revevolcloud.com\", \"annie@revevolcloud.com\" ],\n" +
                                    "    \"changePasswordAtNextLogin\" : false,\n" +
                                    "    \"creationTime\" : \"2013-09-10T15:08:00.000Z\",\n" +
                                    "    \"customerId\" : \"C02g0ie33\",\n" +
                                    "    \"emails\" : [ {\n" +
                                    "      \"address\" : \"anouche.sariand@revevolcloud.com\",\n" +
                                    "      \"primary\" : true\n" +
                                    "    } ],\n" +
                                    "    \"id\" : \"100671467136357495231\",\n" +
                                    "    \"includeInGlobalAddressList\" : true,\n" +
                                    "    \"ipWhitelisted\" : false,\n" +
                                    "    \"isAdmin\" : true,\n" +
                                    "    \"isDelegatedAdmin\" : false,\n" +
                                    "    \"isMailboxSetup\" : true,\n" +
                                    "    \"kind\" : \"admin#directory#user\",\n" +
                                    "    \"lastLoginTime\" : \"2015-03-23T10:14:16.000Z\",\n" +
                                    "    \"name\" : {\n" +
                                    "      \"familyName\" : \"Sariand\",\n" +
                                    "      \"fullName\" : \"Anouche Sariand\",\n" +
                                    "      \"givenName\" : \"Anouche\"\n" +
                                    "    },\n" +
                                    "    \"orgUnitPath\" : \"/\",\n" +
                                    "    \"primaryEmail\" : \"anouche.sariand@revevolcloud.com\",\n" +
                                    "    \"suspended\" : false,\n" +
                                    "    \"etag\" : \"\\\"0As_BCEXgB6xS4FDN2QngtrqYl0/dQrWwNm_aGMLGF1jIpiWnC407e8\\\"\"\n" +
                                    "  }]}");
                        }
                    }
                });

        final Users successResult = executeHttpRequestToGetUsers();

        assertEquals(2, attemptCounts[0]);
        assertEquals(1, successResult.getUsers().size());
    }

    private Users executeHttpRequestToGetUsers() throws IOException, InterruptedException {
        final String[] error = {null};
        final Users[] successResult = {null};
        GoogleAsyncClient.executeAsync(directory.users().list().setCustomer("my_customer"), new FutureCallback<Users>() {
            @Override
            public void completed(Users result) {
                successResult[0] = result;
                done[0] = true;
            }

            @Override
            public void failed(Exception ex) {
                ex.printStackTrace();
                error[0] = "HTTP request should not fail";
                done[0] = true;
            }

            @Override
            public void cancelled() {
                error[0] = "HTTP request should not be cancelled";
                done[0] = true;
            }
        });

        while (!done[0]) {
            Thread.sleep(100L);
        }

        if (error[0] != null) {
            fail(error[0]);
        }
        return successResult[0];
    }
}