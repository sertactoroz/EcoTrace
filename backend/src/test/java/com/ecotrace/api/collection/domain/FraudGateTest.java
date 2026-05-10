package com.ecotrace.api.collection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionEvidence;
import com.ecotrace.api.collection.entity.PhotoKind;
import com.ecotrace.api.config.properties.FraudProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudGateTest {

    private final FraudProperties config = new FraudProperties(50, 30, 10, true);
    private final FraudGate gate = new FraudGate(config);

    @Test
    void passes_when_all_signals_within_thresholds() {
        Collection c = collection(BigDecimal.valueOf(20), 60);
        assertThat(gate.evaluate(c, List.of(after()), 5L)).isEmpty();
    }

    @Test
    void flags_distance_too_far() {
        Collection c = collection(BigDecimal.valueOf(100), 60);
        var failure = gate.evaluate(c, List.of(after()), 0L);
        assertThat(failure).isPresent();
        assertThat(failure.get().reason()).isEqualTo(FraudGate.Reason.DISTANCE_TOO_FAR);
    }

    @Test
    void flags_dwell_too_short() {
        Collection c = collection(BigDecimal.valueOf(20), 5);
        var failure = gate.evaluate(c, List.of(after()), 0L);
        assertThat(failure).isPresent();
        assertThat(failure.get().reason()).isEqualTo(FraudGate.Reason.DWELL_TOO_SHORT);
    }

    @Test
    void flags_missing_after_photo_when_required() {
        Collection c = collection(BigDecimal.valueOf(20), 60);
        var failure = gate.evaluate(c, List.of(before()), 0L);
        assertThat(failure).isPresent();
        assertThat(failure.get().reason()).isEqualTo(FraudGate.Reason.MISSING_AFTER_PHOTO);
    }

    @Test
    void allows_missing_after_photo_when_not_required() {
        FraudProperties lenient = new FraudProperties(50, 30, 10, false);
        FraudGate lenientGate = new FraudGate(lenient);
        Collection c = collection(BigDecimal.valueOf(20), 60);
        assertThat(lenientGate.evaluate(c, List.of(before()), 0L)).isEmpty();
    }

    @Test
    void flags_velocity_exceeded() {
        Collection c = collection(BigDecimal.valueOf(20), 60);
        var failure = gate.evaluate(c, List.of(after()), 11L);
        assertThat(failure).isPresent();
        assertThat(failure.get().reason()).isEqualTo(FraudGate.Reason.VELOCITY_EXCEEDED);
    }

    @Test
    void velocity_at_max_is_allowed() {
        Collection c = collection(BigDecimal.valueOf(20), 60);
        assertThat(gate.evaluate(c, List.of(after()), 10L)).isEmpty();
    }

    @Test
    void skips_distance_when_null() {
        Collection c = collection(null, 60);
        assertThat(gate.evaluate(c, List.of(after()), 0L)).isEmpty();
    }

    @Test
    void skips_dwell_when_null() {
        Collection c = collection(BigDecimal.valueOf(20), null);
        assertThat(gate.evaluate(c, List.of(after()), 0L)).isEmpty();
    }

    private static Collection collection(BigDecimal distance, Integer dwell) {
        Collection c = new Collection();
        c.setDistanceFromPinM(distance);
        c.setDwellSeconds(dwell);
        return c;
    }

    private static CollectionEvidence after() {
        CollectionEvidence e = new CollectionEvidence();
        e.setKind(PhotoKind.AFTER);
        return e;
    }

    private static CollectionEvidence before() {
        CollectionEvidence e = new CollectionEvidence();
        e.setKind(PhotoKind.BEFORE);
        return e;
    }
}
