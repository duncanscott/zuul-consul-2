package org.jadetipi.zuulconsul.consul

import io.vertx.ext.consul.Coordinate
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for CoordinateUtil RTT estimation.
 */
class CoordinateUtilSpec extends Specification {

    // ==================== RTT Estimation Tests ====================

    def "should calculate RTT between two coordinates"() {
        given: "two coordinates with known distance"
        def coord1 = createCoordinate("node1", [0.0f, 0.0f, 0.0f], 0.001f)  // 1ms height
        def coord2 = createCoordinate("node2", [0.003f, 0.004f, 0.0f], 0.002f)  // 2ms height
        // Euclidean distance = sqrt(0.003^2 + 0.004^2) = 0.005 (5ms)

        when:
        def rtt = CoordinateUtil.estimateRtt(coord1, coord2)

        then: "RTT = distance + heights = 5ms + 1ms + 2ms = 8ms (with floating point tolerance)"
        Math.abs(rtt - 8.0f) < 0.001f
    }

    def "should return -1 for null coordinates"() {
        expect:
        CoordinateUtil.estimateRtt(null, createCoordinate("node", [0.0f], 0.0f)) == -1
        CoordinateUtil.estimateRtt(createCoordinate("node", [0.0f], 0.0f), null) == -1
        CoordinateUtil.estimateRtt(null, null) == -1
    }

    def "should return -1 for null vectors"() {
        given:
        def coord1 = createCoordinate("node1", null, 0.0f)
        def coord2 = createCoordinate("node2", [0.0f, 0.0f], 0.0f)

        expect:
        CoordinateUtil.estimateRtt(coord1, coord2) == -1
    }

    def "should return -1 for mismatched vector sizes"() {
        given:
        def coord1 = createCoordinate("node1", [0.0f, 0.0f], 0.0f)
        def coord2 = createCoordinate("node2", [0.0f, 0.0f, 0.0f], 0.0f)

        expect:
        CoordinateUtil.estimateRtt(coord1, coord2) == -1
    }

    def "should return -1 for empty vectors"() {
        given:
        def coord1 = createCoordinate("node1", [], 0.0f)
        def coord2 = createCoordinate("node2", [], 0.0f)

        expect:
        CoordinateUtil.estimateRtt(coord1, coord2) == -1
    }

    def "should calculate zero RTT for same coordinates"() {
        given:
        def coord = createCoordinate("node", [0.001f, 0.002f, 0.003f], 0.0f)

        when:
        def rtt = CoordinateUtil.estimateRtt(coord, coord)

        then: "distance is 0, only heights matter (both 0)"
        rtt == 0.0f
    }

    @Unroll
    def "should calculate RTT correctly for various distances"() {
        given:
        def coord1 = createCoordinate("node1", vec1, height1)
        def coord2 = createCoordinate("node2", vec2, height2)

        expect:
        Math.abs(CoordinateUtil.estimateRtt(coord1, coord2) - expectedRtt) < 0.001f

        where:
        vec1                  | vec2                  | height1 | height2 | expectedRtt
        [0.0f, 0.0f]          | [0.003f, 0.004f]      | 0.0f    | 0.0f    | 5.0f       // 5ms distance only
        [0.0f, 0.0f]          | [0.0f, 0.0f]          | 0.001f  | 0.002f  | 3.0f       // heights only
        [0.001f, 0.0f]        | [0.0f, 0.0f]          | 0.0f    | 0.0f    | 1.0f       // 1ms distance
        [0.0f, 0.0f, 0.0f]    | [0.003f, 0.004f, 0.0f]| 0.001f  | 0.001f  | 7.0f       // 3D distance + heights
    }

    // ==================== Threshold Comparison Tests ====================

    def "isWithinThreshold should return true when RTTs are equal"() {
        expect:
        CoordinateUtil.isWithinThreshold(5.0f, 5.0f, 1.0f)
    }

    def "isWithinThreshold should return true when within threshold"() {
        expect:
        CoordinateUtil.isWithinThreshold(5.0f, 7.0f, 5.0f)   // diff=2, threshold=5
        CoordinateUtil.isWithinThreshold(10.0f, 5.0f, 5.0f)  // diff=5, threshold=5
    }

    def "isWithinThreshold should return false when outside threshold"() {
        expect:
        !CoordinateUtil.isWithinThreshold(5.0f, 15.0f, 5.0f)  // diff=10, threshold=5
        !CoordinateUtil.isWithinThreshold(1.0f, 10.0f, 5.0f)  // diff=9, threshold=5
    }

    @Unroll
    def "isWithinThreshold with rtt1=#rtt1, rtt2=#rtt2, threshold=#threshold should be #expected"() {
        expect:
        CoordinateUtil.isWithinThreshold(rtt1, rtt2, threshold) == expected

        where:
        rtt1  | rtt2  | threshold | expected
        5.0f  | 5.0f  | 0.0f      | true
        5.0f  | 6.0f  | 1.0f      | true
        5.0f  | 6.0f  | 0.5f      | false
        0.0f  | 5.0f  | 5.0f      | true
        0.0f  | 5.1f  | 5.0f      | false
        10.0f | 2.0f  | 8.0f      | true
    }

    // ==================== Helper Methods ====================

    private Coordinate createCoordinate(String node, List<Float> vec, float height) {
        def coord = new Coordinate()
        coord.setNode(node)
        coord.setVec(vec)
        coord.setHeight(height)
        coord.setAdj(0.0f)
        return coord
    }
}
