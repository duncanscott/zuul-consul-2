package org.jadetipi.zuulconsul.couchdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Initializes CouchDB design documents for the zuul-consul database.
 * <p>
 * This class creates the necessary views for querying request stats by timestamp.
 * It only runs if CouchDB logging is enabled via ZUUL_COUCHDB_ENABLED=true.
 * <p>
 * The design document includes:
 * <ul>
 *   <li><b>by_timestamp</b> - View indexed by timestamp for range queries using startkey/endkey</li>
 * </ul>
 * <p>
 * Example query:
 * <pre>
 * GET /zuul-consul/_design/stats/_view/by_timestamp?startkey="2024-01-01T00:00:00"&amp;endkey="2024-01-02T00:00:00"
 * </pre>
 */
public class CouchDbInitializer {

    private static final Logger log = LoggerFactory.getLogger(CouchDbInitializer.class);

    // Environment variable names (matching CouchDbStatsFilter)
    private static final String ENV_ENABLED = "ZUUL_COUCHDB_ENABLED";
    private static final String ENV_URL = "ZUUL_COUCHDB_URL";
    private static final String ENV_USER = "ZUUL_COUCHDB_USER";
    private static final String ENV_PASSWORD = "ZUUL_COUCHDB_PASSWORD";

    // Design document configuration
    private static final String DESIGN_DOC_NAME = "_design/stats";

    /**
     * The design document JSON with views for querying stats.
     * <p>
     * Views:
     * <ul>
     *   <li>by_timestamp - emits timestamp as key, allows startkey/endkey range queries</li>
     *   <li>by_service - emits [service_name, timestamp] for filtering by service</li>
     *   <li>by_status - emits [http_response_status_code, timestamp] for filtering by status</li>
     *   <li>errors - emits timestamp for documents with error=true</li>
     * </ul>
     */
    private static final String DESIGN_DOC_JSON = """
        {
          "_id": "_design/stats",
          "views": {
            "by_timestamp": {
              "map": "function(doc) { if (doc.type === 'request_stats' && doc.timestamp) { emit(doc.timestamp, null); } }"
            },
            "by_service": {
              "map": "function(doc) { if (doc.type === 'request_stats' && doc.service_name && doc.timestamp) { emit([doc.service_name, doc.timestamp], null); } }"
            },
            "by_status": {
              "map": "function(doc) { if (doc.type === 'request_stats' && doc.http_response_status_code && doc.timestamp) { emit([doc.http_response_status_code, doc.timestamp], null); } }"
            },
            "errors": {
              "map": "function(doc) { if (doc.type === 'request_stats' && doc.error === true && doc.timestamp) { emit(doc.timestamp, null); } }"
            }
          },
          "language": "javascript"
        }
        """;

    private final HttpClient httpClient;
    private final String couchDbUrl;
    private final String authHeader;
    private final boolean enabled;

    public CouchDbInitializer() {
        this.enabled = "true".equalsIgnoreCase(System.getenv(ENV_ENABLED));
        this.couchDbUrl = System.getenv(ENV_URL);

        String user = System.getenv(ENV_USER);
        String password = System.getenv(ENV_PASSWORD);

        if (enabled && user != null && password != null) {
            String credentials = user + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        } else {
            this.authHeader = null;
        }

        if (enabled) {
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        } else {
            this.httpClient = null;
        }
    }

    /**
     * Initialize CouchDB views if CouchDB logging is enabled.
     * <p>
     * This method:
     * <ol>
     *   <li>Checks if CouchDB logging is enabled</li>
     *   <li>Verifies the database exists</li>
     *   <li>Creates or updates the design document with views</li>
     * </ol>
     */
    public void initialize() {
        if (!enabled) {
            log.debug("CouchDB logging disabled, skipping view initialization");
            return;
        }

        if (couchDbUrl == null || couchDbUrl.isEmpty()) {
            log.warn("CouchDB enabled but ZUUL_COUCHDB_URL not set, skipping initialization");
            return;
        }

        log.info("Initializing CouchDB views at {}", couchDbUrl);

        try {
            // Check if database exists
            if (!databaseExists()) {
                log.warn("CouchDB database does not exist at {}, skipping view initialization", couchDbUrl);
                return;
            }

            // Check if design document exists
            String designDocUrl = couchDbUrl + "/" + DESIGN_DOC_NAME;
            HttpResponse<String> getResponse = sendRequest("GET", designDocUrl, null);

            if (getResponse.statusCode() == 200) {
                // Design document exists, check if update needed
                String existingDoc = getResponse.body();
                if (existingDoc.contains("by_timestamp") &&
                    existingDoc.contains("by_service") &&
                    existingDoc.contains("by_status") &&
                    existingDoc.contains("errors")) {
                    log.info("CouchDB design document already exists with all views");
                    return;
                }

                // Extract _rev for update
                String rev = extractRev(existingDoc);
                if (rev != null) {
                    updateDesignDocument(designDocUrl, rev);
                }
            } else if (getResponse.statusCode() == 404) {
                // Create new design document
                createDesignDocument(designDocUrl);
            } else {
                log.warn("Unexpected response checking design document: {} {}",
                    getResponse.statusCode(), getResponse.body());
            }

        } catch (Exception e) {
            log.warn("Failed to initialize CouchDB views: {}", e.getMessage());
            // Don't fail startup - CouchDB views are optional
        }
    }

    private boolean databaseExists() throws Exception {
        HttpResponse<String> response = sendRequest("GET", couchDbUrl, null);
        return response.statusCode() == 200;
    }

    private void createDesignDocument(String designDocUrl) throws Exception {
        log.info("Creating CouchDB design document: {}", DESIGN_DOC_NAME);

        HttpResponse<String> response = sendRequest("PUT", designDocUrl, DESIGN_DOC_JSON);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("CouchDB design document created successfully");
        } else {
            log.warn("Failed to create design document: {} {}", response.statusCode(), response.body());
        }
    }

    private void updateDesignDocument(String designDocUrl, String rev) throws Exception {
        log.info("Updating CouchDB design document: {}", DESIGN_DOC_NAME);

        // Add _rev to the design document for update
        String docWithRev = DESIGN_DOC_JSON.replaceFirst(
            "\\{",
            "{ \"_rev\": \"" + rev + "\","
        );

        HttpResponse<String> response = sendRequest("PUT", designDocUrl, docWithRev);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("CouchDB design document updated successfully");
        } else {
            log.warn("Failed to update design document: {} {}", response.statusCode(), response.body());
        }
    }

    private String extractRev(String json) {
        // Simple extraction of _rev value from JSON
        int revIndex = json.indexOf("\"_rev\"");
        if (revIndex < 0) return null;

        int colonIndex = json.indexOf(":", revIndex);
        if (colonIndex < 0) return null;

        int startQuote = json.indexOf("\"", colonIndex);
        if (startQuote < 0) return null;

        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;

        return json.substring(startQuote + 1, endQuote);
    }

    private HttpResponse<String> sendRequest(String method, String url, String body) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30));

        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }

        if (body != null) {
            requestBuilder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
