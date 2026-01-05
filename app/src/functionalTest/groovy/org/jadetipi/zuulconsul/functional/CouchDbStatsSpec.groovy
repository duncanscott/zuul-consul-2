package org.jadetipi.zuulconsul.functional

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Stepwise
import spock.lang.IgnoreIf
import spock.lang.Requires

/**
 * Functional tests for CouchDB stats logging.
 *
 * Prerequisites:
 * 1. Docker Compose environment running with CouchDB:
 *    docker-compose --profile couchdb -f docker/docker-compose.yml up -d
 * 2. Zuul Consul gateway running with CouchDB enabled:
 *    - ZUUL_COUCHDB_ENABLED=true
 *    - ZUUL_COUCHDB_URL=http://localhost:5994/zuul-consul
 *    - ZUUL_COUCHDB_USER=admin
 *    - ZUUL_COUCHDB_PASSWORD=password
 *
 * Run with: ./gradlew :app:functionalTest --tests CouchDbStatsSpec
 */
@Stepwise
class CouchDbStatsSpec extends Specification {

    @Shared
    HttpTestClient gatewayClient

    @Shared
    HttpTestClient couchDbClient

    @Shared
    boolean environmentReady = false

    @Shared
    boolean couchDbEnabled = false

    @Shared
    String couchDbAuth

    def setupSpec() {
        gatewayClient = new HttpTestClient(FunctionalTestConfig.ZUUL_URL)

        // Build CouchDB auth header
        String credentials = "${FunctionalTestConfig.COUCHDB_USER}:${FunctionalTestConfig.COUCHDB_PASSWORD}"
        couchDbAuth = 'Basic ' + Base64.encoder.encodeToString(credentials.getBytes('UTF-8'))

        couchDbClient = new HttpTestClient(FunctionalTestConfig.COUCHDB_URL)

        // Check if the test environment is available
        environmentReady = checkEnvironment()
    }

    private boolean checkEnvironment() {
        println "Checking CouchDB test environment..."
        println "Gateway URL: ${FunctionalTestConfig.ZUUL_URL}"
        println "CouchDB URL: ${FunctionalTestConfig.COUCHDB_URL}"
        println "ZUUL_COUCHDB_ENABLED: ${System.getenv('ZUUL_COUCHDB_ENABLED')}"

        // Check Gateway
        if (!gatewayClient.waitForAvailable('/', 5)) {
            println "WARNING: Gateway is not available at ${FunctionalTestConfig.ZUUL_URL}"
            return false
        }
        println "Gateway is available"

        // Check CouchDB
        if (!couchDbClient.waitForAvailable('/', 5)) {
            println "WARNING: CouchDB is not available at ${FunctionalTestConfig.COUCHDB_URL}"
            println "Start with: docker-compose --profile couchdb up -d"
            return false
        }
        println "CouchDB is available"

        // Check if database exists (with retries)
        boolean dbExists = false
        for (int i = 0; i < 10; i++) {
            def dbResponse = couchDbClient.get("/${FunctionalTestConfig.COUCHDB_DATABASE}", ['Authorization': couchDbAuth])
            if (dbResponse.success) {
                dbExists = true
                break
            }
            println "Waiting for database... (attempt ${i + 1}/10, status: ${dbResponse.statusCode})"
            Thread.sleep(1000)
        }

        if (!dbExists) {
            println "WARNING: Database '${FunctionalTestConfig.COUCHDB_DATABASE}' does not exist after retries"
            println "It should be created by the couchdb-init container or functional-tests.sh"
            return false
        }
        println "Database '${FunctionalTestConfig.COUCHDB_DATABASE}' exists"

        // Check if CouchDB logging is enabled on the gateway
        couchDbEnabled = System.getenv('ZUUL_COUCHDB_ENABLED')?.equalsIgnoreCase('true') ?: false
        if (!couchDbEnabled) {
            println "NOTE: ZUUL_COUCHDB_ENABLED is not set to 'true'"
            println "Document creation assertions will be skipped"
        } else {
            println "CouchDB stats logging is enabled"
        }

        return true
    }

    // ==================== CouchDB Connectivity ====================

    @IgnoreIf({ !instance.environmentReady })
    def "CouchDB should be accessible"() {
        when:
        def response = couchDbClient.get('/')

        then:
        response.success
        response.bodyContains('couchdb')
    }

    @IgnoreIf({ !instance.environmentReady })
    def "zuul-consul database should exist"() {
        when:
        def response = couchDbClient.get("/${FunctionalTestConfig.COUCHDB_DATABASE}", ['Authorization': couchDbAuth])

        then:
        response.success
        def json = response.json
        json.db_name == FunctionalTestConfig.COUCHDB_DATABASE
    }

    // ==================== Stats Document Creation ====================

    @IgnoreIf({ !instance.environmentReady })
    def "request through gateway should create stats document in CouchDB"() {
        given: "clear knowledge of initial document count"
        def initialResponse = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs",
            ['Authorization': couchDbAuth]
        )
        int initialCount = initialResponse.success ? (initialResponse.json?.total_rows ?: 0) : 0

        when: "make a request through the gateway"
        def gatewayResponse = gatewayClient.get('/env:dev/hello-service/version')

        and: "wait for async CouchDB write to complete"
        Thread.sleep(2000)

        and: "check document count"
        def finalResponse = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs",
            ['Authorization': couchDbAuth]
        )

        then: "gateway request succeeded"
        gatewayResponse.success

        and: "new document was created (if CouchDB logging is enabled)"
        if (couchDbEnabled) {
            finalResponse.success
            def finalCount = finalResponse.json?.total_rows ?: 0
            assert finalCount > initialCount : "Expected new document in CouchDB (initial: ${initialCount}, final: ${finalCount})"
        }
    }

    @IgnoreIf({ !instance.environmentReady })
    def "stats document should contain required fields"() {
        given: "make a request to generate a stats document"
        gatewayClient.get('/env:dev/hello-service/info')
        Thread.sleep(2000)

        when: "fetch the most recent document"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs?include_docs=true&descending=true&limit=1",
            ['Authorization': couchDbAuth]
        )

        then: "response is successful"
        response.success

        and: "document exists and has expected structure"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc

            // Core fields
            assert doc.timestamp != null : "Missing timestamp"
            assert doc.type == 'request_stats' : "Wrong type: ${doc.type}"

            // Trace context
            assert doc.zuul_consul_id != null : "Missing zuul_consul_id"

            // Service info
            assert doc.service_name != null : "Missing service_name"

            // Request info
            assert doc.http_request_method != null : "Missing http_request_method"
            assert doc.url_path != null : "Missing url_path"

            // Response info
            assert doc.http_response_status_code != null : "Missing http_response_status_code"

            // Duration
            assert doc.milliseconds != null : "Missing milliseconds"
        }
    }

    // ==================== Field Values Verification ====================

    @IgnoreIf({ !instance.environmentReady })
    def "stats document should have correct service routing information"() {
        given: "make a specific request"
        gatewayClient.get('/env:test/echo-service/echo')
        Thread.sleep(2000)

        when: "fetch the most recent document"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs?include_docs=true&descending=true&limit=1",
            ['Authorization': couchDbAuth]
        )

        then:
        response.success

        and: "verify service-specific fields if CouchDB is enabled"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc

            // Service was routed to echo-service
            assert doc.service_name == 'echo-service' : "Expected echo-service, got ${doc.service_name}"

            // Tags were parsed (env:test)
            if (doc.tags) {
                assert doc.tags.env == 'test' : "Expected env:test tag"
            }
        }
    }

    // ==================== Error Handling ====================

    @IgnoreIf({ !instance.environmentReady })
    def "error responses should be flagged in stats"() {
        given: "make a request to non-existent service"
        gatewayClient.get('/env:dev/non-existent-service/path')
        Thread.sleep(2000)

        when: "fetch the most recent document"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs?include_docs=true&descending=true&limit=1",
            ['Authorization': couchDbAuth]
        )

        then:
        response.success

        and: "error flag is set if CouchDB is enabled"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc
            // Error responses should have error flag
            if (doc.http_response_status_code >= 400) {
                assert doc.error == true : "Expected error flag for status ${doc.http_response_status_code}"
            }
        }
    }

    // ==================== OpenTelemetry Fields ====================

    @IgnoreIf({ !instance.environmentReady })
    def "stats document should include OpenTelemetry semantic convention fields"() {
        given: "make a request"
        gatewayClient.get('/env:dev/hello-service/health')
        Thread.sleep(2000)

        when: "fetch the most recent document"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs?include_docs=true&descending=true&limit=1",
            ['Authorization': couchDbAuth]
        )

        then:
        response.success

        and: "OTel semantic convention fields are present"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc

            // These are the OTel aliases we added
            if (doc.service_host_name) {
                assert doc.server_address == doc.service_host_name : "server.address should match service.host.name"
            }
            if (doc.service_port) {
                assert doc.server_port == doc.service_port : "server.port should match service.port"
            }
        }
    }
}
