package com.ecotrace.api.collection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeoDistanceTest {

    @Test
    void zero_distance_for_same_point() {
        double m = GeoDistance.haversineMeters(49.4093, 8.6940, 49.4093, 8.6940);
        assertThat(m).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void roughly_111km_per_degree_of_latitude() {
        double m = GeoDistance.haversineMeters(49.0000, 8.6940, 50.0000, 8.6940);
        assertThat(m).isBetween(110_000.0, 112_000.0);
    }

    @Test
    void short_distance_is_accurate_at_meter_scale() {
        // ~10 meters north at Heidelberg's latitude
        double oneMeterDeg = 1.0 / 111_320.0;
        double m = GeoDistance.haversineMeters(
                49.4093, 8.6940,
                49.4093 + 10 * oneMeterDeg, 8.6940);
        assertThat(m).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.5));
    }
}
