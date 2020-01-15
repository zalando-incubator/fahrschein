package org.zalando.fahrschein.metrics;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class MicrometerLastActivityMetricsCollectorTest {

    private static final Tag EVENT_TYPE_TEST_EVENT = Tag.of("event_type", "test_event");

    private SimpleMeterRegistry meterRegistry;
    private MicrometerLastActivityMetricsCollector metricsCollector;
    private TestClock clock;

    @Before
    public void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = new TestClock(Clock.fixed(Instant.parse("2019-01-12T15:00:00Z"), ZoneId.of("UTC")));

        metricsCollector = new MicrometerLastActivityMetricsCollector(meterRegistry, Collections.singletonList(EVENT_TYPE_TEST_EVENT), clock);
    }

    @Test
    public void shouldMeasureTimeSinceLastMessageReceived() {
        metricsCollector.markMessageReceived();
        clock.advance(Duration.ofSeconds(5));

        assertThatGaugeEquals("nakadi_last_message_received", 5000);
    }

    @Test
    public void shouldMeasureTimeSinceLastMessageSuccessfullyProcessed() {
        metricsCollector.markMessageSuccessfullyProcessed();
        clock.advance(Duration.ofSeconds(5));

        assertThatGaugeEquals("nakadi_last_message_successfully_processed", 5000);
    }

    @Test
    public void shouldMeasureTimeSinceLastEventReceived() {
        metricsCollector.markEventsReceived(5);
        clock.advance(Duration.ofSeconds(5));

        assertThatGaugeEquals("nakadi_last_event_received", 5000);
    }

    @Test
    public void shouldMeasureTimeSinceLastErrorHappened() {
        metricsCollector.markErrorWhileConsuming();
        clock.advance(Duration.ofSeconds(5));

        assertThatGaugeEquals("nakadi_last_error_happened", 5000);
    }

    @Test
    public void shouldMeasureTimeSinceLastReconnect() {
        metricsCollector.markReconnection();
        clock.advance(Duration.ofSeconds(5));

        assertThatGaugeEquals("nakadi_last_reconnect", 5000);
    }

    private void assertThatGaugeEquals(String gaugeName, double value) {
        Gauge gauge = meterRegistry.find(gaugeName)
              .tags(Collections.singletonList(EVENT_TYPE_TEST_EVENT))
              .gauge();

        assertThat(gauge.value(), equalTo(value));
    }

    private static class TestClock extends Clock {
        private Clock delegate;

        private TestClock(final Clock clock) {
            this.delegate = clock;
        }

        public void advance(final Duration duration) {
            this.delegate = Clock.fixed(delegate.instant().plus(duration), delegate.getZone());
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            return delegate.instant();
        }
    }
}