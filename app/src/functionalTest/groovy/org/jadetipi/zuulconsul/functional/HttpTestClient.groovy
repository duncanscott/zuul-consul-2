package org.jadetipi.zuulconsul.functional

import groovy.transform.CompileStatic
import groovy.json.JsonSlurper

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * Simple HTTP client for functional tests.
 * Supports both HTTP and HTTPS (with optional self-signed certificate trust).
 */
@CompileStatic
class HttpTestClient {

    private final HttpClient client
    private final String baseUrl

    HttpTestClient(String baseUrl, boolean trustSelfSigned = false) {
        this.baseUrl = baseUrl.endsWith('/') ? baseUrl[0..-2] : baseUrl

        def builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(FunctionalTestConfig.CONNECT_TIMEOUT_MS))

        // For HTTPS with self-signed certificates
        if (trustSelfSigned && baseUrl.startsWith('https://')) {
            builder.sslContext(createTrustAllSSLContext())
        }

        this.client = builder.build()
    }

    /**
     * Create an SSL context that trusts all certificates (for testing only).
     */
    private static SSLContext createTrustAllSSLContext() {
        TrustManager[] trustAllCerts = [
            new X509TrustManager() {
                X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
                void checkClientTrusted(X509Certificate[] certs, String authType) {}
                void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        ] as TrustManager[]

        SSLContext sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, new SecureRandom())
        return sslContext
    }

    /**
     * Make a GET request and return the response.
     */
    HttpTestResponse get(String path, Map<String, String> headers = [:]) {
        def requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl}${path}"))
            .timeout(Duration.ofMillis(FunctionalTestConfig.READ_TIMEOUT_MS))
            .GET()

        headers.each { k, v -> requestBuilder.header(k, v) }

        def request = requestBuilder.build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        return new HttpTestResponse(
            statusCode: response.statusCode(),
            body: response.body(),
            headers: response.headers().map()
        )
    }

    /**
     * Make an OPTIONS request (for CORS preflight testing).
     */
    HttpTestResponse options(String path, Map<String, String> headers = [:]) {
        def requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl}${path}"))
            .timeout(Duration.ofMillis(FunctionalTestConfig.READ_TIMEOUT_MS))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())

        // Standard CORS preflight headers
        requestBuilder.header("Origin", "http://example.com")
        requestBuilder.header("Access-Control-Request-Method", "GET")
        requestBuilder.header("Access-Control-Request-Headers", "Authorization, Content-Type")

        headers.each { k, v -> requestBuilder.header(k, v) }

        def request = requestBuilder.build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        return new HttpTestResponse(
            statusCode: response.statusCode(),
            body: response.body(),
            headers: response.headers().map()
        )
    }

    /**
     * Check if the service is available (returns 2xx on the given path).
     */
    boolean isAvailable(String path = '/') {
        try {
            def response = get(path)
            return response.statusCode >= 200 && response.statusCode < 300
        } catch (Exception e) {
            return false
        }
    }

    /**
     * Wait for the service to become available.
     */
    boolean waitForAvailable(String path = '/', int maxRetries = FunctionalTestConfig.MAX_RETRIES) {
        for (int i = 0; i < maxRetries; i++) {
            if (isAvailable(path)) {
                return true
            }
            Thread.sleep(FunctionalTestConfig.RETRY_DELAY_MS)
        }
        return false
    }

    /**
     * Response wrapper for test assertions.
     */
    static class HttpTestResponse {
        int statusCode
        String body
        Map<String, List<String>> headers

        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300
        }

        boolean isError() {
            return statusCode >= 400
        }

        /**
         * Parse body as JSON.
         */
        Object getJson() {
            return new JsonSlurper().parseText(body)
        }

        /**
         * Check if body contains a string.
         */
        boolean bodyContains(String text) {
            return body?.contains(text)
        }
    }
}
