package org.jadetipi.zuulconsul.consul;

import io.vertx.ext.consul.Coordinate;

import java.util.List;

/**
 * Utility class for calculating estimated RTT between Consul nodes
 * using Vivaldi network coordinates.
 * <p>
 * Consul uses a Vivaldi coordinate system where each node has a position
 * in a virtual coordinate space. The estimated RTT between two nodes is
 * calculated as the Euclidean distance plus height adjustments.
 * <p>
 * Formula: RTT = euclidean_distance(vec1, vec2) + height1 + height2
 * <p>
 * The adjustment values are typically small and used for fine-tuning.
 */
public final class CoordinateUtil {

    private CoordinateUtil() {
        // Utility class
    }

    /**
     * Calculate the estimated RTT in milliseconds between two nodes.
     *
     * @param c1 first node's coordinate
     * @param c2 second node's coordinate
     * @return estimated RTT in milliseconds, or -1 if coordinates are invalid
     */
    public static float estimateRtt(Coordinate c1, Coordinate c2) {
        if (c1 == null || c2 == null) {
            return -1;
        }

        List<Float> vec1 = c1.getVec();
        List<Float> vec2 = c2.getVec();

        if (vec1 == null || vec2 == null || vec1.size() != vec2.size() || vec1.isEmpty()) {
            return -1;
        }

        // Calculate Euclidean distance between coordinate vectors
        float sumSquares = 0;
        for (int i = 0; i < vec1.size(); i++) {
            float diff = vec1.get(i) - vec2.get(i);
            sumSquares += diff * diff;
        }
        float distance = (float) Math.sqrt(sumSquares);

        // Add heights (represents additional latency not captured by coordinates)
        float totalHeight = c1.getHeight() + c2.getHeight();

        // RTT estimate in seconds, convert to milliseconds
        float rttSeconds = distance + totalHeight;
        return rttSeconds * 1000;
    }

    /**
     * Check if two RTT values are within a threshold of each other.
     *
     * @param rtt1        first RTT in milliseconds
     * @param rtt2        second RTT in milliseconds
     * @param thresholdMs threshold in milliseconds
     * @return true if the RTTs are within the threshold
     */
    public static boolean isWithinThreshold(float rtt1, float rtt2, float thresholdMs) {
        return Math.abs(rtt1 - rtt2) <= thresholdMs;
    }
}
