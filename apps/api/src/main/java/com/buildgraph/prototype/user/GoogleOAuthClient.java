package com.buildgraph.prototype.user;

public interface GoogleOAuthClient {
    GoogleOAuthPendingLogin exchangeAuthorizationCode(String authorizationCode, String redirectUri, String redirectPath);
}
