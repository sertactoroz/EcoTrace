package com.ecotrace.api.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.gamification.event.PointsAwarded;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class PointsAwardedListenerWiringTest {

    @Test
    void listener_runs_after_commit_so_rolled_back_verify_does_not_inflate_leaderboard() throws Exception {
        Method on = PointsAwardedListener.class.getDeclaredMethod("on", PointsAwarded.class);

        TransactionalEventListener annotation = on.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation)
                .as("PointsAwardedListener.on must be @TransactionalEventListener — see doc 06 Rule 5")
                .isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
