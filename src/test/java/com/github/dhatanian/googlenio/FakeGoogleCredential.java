package com.github.dhatanian.googlenio;

import com.google.api.client.http.*;

import java.io.IOException;

public class FakeGoogleCredential implements
        HttpExecuteInterceptor,
        HttpRequestInitializer,
        HttpUnsuccessfulResponseHandler {

    @Override
    public void intercept(HttpRequest request) throws IOException {
        //Do nothing
    }

    @Override
    public void initialize(HttpRequest request) throws IOException {
        request.setInterceptor(this);
        request.setUnsuccessfulResponseHandler(this);
    }

    @Override
    public boolean handleResponse(HttpRequest request, HttpResponse response, boolean supportsRetry) throws IOException {
        //Simulate a refresh token
        return true;
    }
}
