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
 *
 * Field names follow Elastic Common Schema (ECS) and OpenTelemetry conventions.
 * Document _id is set to trace.id for easy lookup by trace ID.
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
    def "stats document should contain required ECS fields"() {
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

        and: "document exists and has expected ECS structure"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc

            // Core fields (ECS)
            assert doc['@timestamp'] != null : "Missing @timestamp"
            assert doc.type == 'request_stats' : "Wrong type: ${doc.type}"

            // Trace context (ECS trace fields)
            assert doc['trace.id'] != null : "Missing trace.id"
            // Document _id should match trace.id
            assert doc._id == doc['trace.id'] : "Document _id should match trace.id"

            // Service info (ECS)
            assert doc['service.name'] != null : "Missing service.name"

            // Request info (ECS HTTP fields)
            assert doc['http.request.method'] != null : "Missing http.request.method"
            assert doc['url.path'] != null : "Missing url.path"

            // Response info (ECS HTTP fields)
            assert doc['http.response.status_code'] != null : "Missing http.response.status_code"

            // Duration (ECS event field)
            assert doc['event.duration'] != null : "Missing event.duration"

            // Event outcome (ECS)
            assert doc['event.outcome'] != null : "Missing event.outcome"
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
            assert doc['service.name'] == 'echo-service' : "Expected echo-service, got ${doc['service.name']}"

            // Labels were parsed (env:test)
            assert doc['labels.env'] == 'test' : "Expected labels.env=test"
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

        and: "event.outcome is failure if CouchDB is enabled"
        if (couchDbEnabled && response.json?.rows?.size() > 0) {
            def doc = response.json.rows[0].doc
            // Error responses should have event.outcome=failure
            if (doc['http.response.status_code'] >= 400) {
                assert doc['event.outcome'] == 'failure' : "Expected event.outcome=failure for status ${doc['http.response.status_code']}"
                assert doc['error.type'] != null : "Expected error.type for failure"
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

            // Server fields (OTel)
            assert doc['server.address'] != null : "Missing server.address"
            assert doc['server.port'] != null : "Missing server.port"

            // URL fields (ECS/OTel)
            assert doc['url.full'] != null : "Missing url.full"
            assert doc['url.original'] != null : "Missing url.original"
        }
    }

    // ==================== Document ID by Trace ID ====================

    @IgnoreIf({ !instance.environmentReady })
    def "document can be looked up by trace ID"() {
        given: "make a request and get trace ID from response"
        gatewayClient.get('/env:dev/hello-service/version')
        Thread.sleep(2000)

        when: "fetch the most recent document to get its trace ID"
        def allDocsResponse = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_all_docs?include_docs=true&descending=true&limit=1",
            ['Authorization': couchDbAuth]
        )

        then: "we can look up the document directly by trace ID"
        if (couchDbEnabled && allDocsResponse.json?.rows?.size() > 0) {
            def doc = allDocsResponse.json.rows[0].doc
            String traceId = doc['trace.id']

            // Fetch document directly by trace ID
            def directResponse = couchDbClient.get(
                "/${FunctionalTestConfig.COUCHDB_DATABASE}/${traceId}",
                ['Authorization': couchDbAuth]
            )

            assert directResponse.success : "Should be able to fetch document by trace ID"
            assert directResponse.json['trace.id'] == traceId : "Retrieved document should have same trace.id"
        }
    }

    // ==================== CouchDB View Tests ====================

    @IgnoreIf({ !instance.environmentReady })
    def "by_timestamp view should exist and be queryable"() {
        when: "query the by_timestamp view"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_design/stats/_view/by_timestamp?limit=1",
            ['Authorization': couchDbAuth]
        )

        then: "view exists and returns results"
        response.success
        response.json.rows != null
    }

    @IgnoreIf({ !instance.environmentReady })
    def "by_timestamp view should support range queries with startkey and endkey"() {
        given: "record timestamp before making requests"
        def startTime = java.time.OffsetDateTime.now().minusMinutes(1)
        def startKey = startTime.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        and: "make several requests through the gateway"
        gatewayClient.get('/env:dev/hello-service/version')
        gatewayClient.get('/env:dev/echo-service/echo')
        Thread.sleep(2000)

        and: "record timestamp after requests"
        def endTime = java.time.OffsetDateTime.now().plusMinutes(1)
        def endKey = endTime.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        when: "query the view with time range"
        def encodedStartKey = URLEncoder.encode("\"${startKey}\"", 'UTF-8')
        def encodedEndKey = URLEncoder.encode("\"${endKey}\"", 'UTF-8')
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_design/stats/_view/by_timestamp?startkey=${encodedStartKey}&endkey=${encodedEndKey}&include_docs=true",
            ['Authorization': couchDbAuth]
        )

        then: "query succeeds"
        response.success

        and: "results are returned within the time range (if CouchDB logging is enabled)"
        if (couchDbEnabled) {
            def rows = response.json?.rows ?: []
            println "Found ${rows.size()} documents in time range"

            assert rows.size() > 0 : "Expected at least one document in the time range"

            // Verify all returned documents have @timestamp within the range
            rows.each { row ->
                def doc = row.doc
                assert doc['@timestamp'] != null : "Document missing @timestamp"
                assert doc['@timestamp'] >= startKey : "@timestamp ${doc['@timestamp']} is before startKey ${startKey}"
                assert doc['@timestamp'] <= endKey : "@timestamp ${doc['@timestamp']} is after endKey ${endKey}"
            }
        }
    }

    @IgnoreIf({ !instance.environmentReady })
    def "by_service view should filter by service name"() {
        given: "make requests to different services"
        gatewayClient.get('/env:dev/hello-service/version')
        gatewayClient.get('/env:dev/echo-service/echo')
        Thread.sleep(2000)

        when: "query the view for hello-service only"
        def encodedStartKey = URLEncoder.encode('["hello-service",""]', 'UTF-8')
        def encodedEndKey = URLEncoder.encode('["hello-service","\\ufff0"]', 'UTF-8')
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_design/stats/_view/by_service?startkey=${encodedStartKey}&endkey=${encodedEndKey}&include_docs=true",
            ['Authorization': couchDbAuth]
        )

        then: "query succeeds"
        response.success

        and: "only hello-service documents are returned (if CouchDB logging is enabled)"
        if (couchDbEnabled) {
            def rows = response.json?.rows ?: []
            println "Found ${rows.size()} hello-service documents"

            rows.each { row ->
                def doc = row.doc
                assert doc['service.name'] == 'hello-service' : "Expected hello-service, got ${doc['service.name']}"
            }
        }
    }

    @IgnoreIf({ !instance.environmentReady })
    def "by_status view should filter by HTTP status code"() {
        given: "make requests that will succeed and fail"
        gatewayClient.get('/env:dev/hello-service/version')  // should return 200
        gatewayClient.get('/env:dev/non-existent-service/path')  // should return 404
        Thread.sleep(2000)

        when: "query the view for 200 status only"
        def encodedStartKey = URLEncoder.encode('[200,""]', 'UTF-8')
        def encodedEndKey = URLEncoder.encode('[200,"\\ufff0"]', 'UTF-8')
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_design/stats/_view/by_status?startkey=${encodedStartKey}&endkey=${encodedEndKey}&include_docs=true",
            ['Authorization': couchDbAuth]
        )

        then: "query succeeds"
        response.success

        and: "only 200 status documents are returned (if CouchDB logging is enabled)"
        if (couchDbEnabled) {
            def rows = response.json?.rows ?: []
            println "Found ${rows.size()} documents with status 200"

            assert rows.size() > 0 : "Expected at least one document with status 200"

            rows.each { row ->
                def doc = row.doc
                assert doc['http.response.status_code'] == 200 : "Expected status 200, got ${doc['http.response.status_code']}"
            }
        }
    }

    @IgnoreIf({ !instance.environmentReady })
    def "errors view should only return error documents"() {
        given: "make a request that will fail (non-existent service)"
        gatewayClient.get('/env:dev/this-service-does-not-exist/path')
        Thread.sleep(2000)

        when: "query the errors view"
        def response = couchDbClient.get(
            "/${FunctionalTestConfig.COUCHDB_DATABASE}/_design/stats/_view/errors?include_docs=true",
            ['Authorization': couchDbAuth]
        )

        then: "query succeeds"
        response.success

        and: "all returned documents have event.outcome=failure (if CouchDB logging is enabled)"
        if (couchDbEnabled) {
            def rows = response.json?.rows ?: []
            println "Found ${rows.size()} error documents"

            rows.each { row ->
                def doc = row.doc
                assert doc['event.outcome'] == 'failure' : "Expected event.outcome=failure for document ${doc._id}"
            }
        }
    }
}
