package org.zalando.fahrschein.metrics;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class MicrometerMetricsCollectorTest {

    private static final Tag EVENT_TYPE_TEST_EVENT = Tag.of("event_type", "test_event");

    private SimpleMeterRegistry meterRegistry;
    private MicrometerMetricsCollector metricsCollector;

    @Before
    public void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsCollector = new MicrometerMetricsCollector(meterRegistry, Collections.singletonList(EVENT_TYPE_TEST_EVENT));
    }

    @Test
    public void shouldIncrementMessagesReceivedCounter() {
        metricsCollector.markMessageReceived();

        assertThatCounterEquals("nakadi_messages_received", 1.0);
    }

    @Test
    public void shouldIncrementEventsReceivedCounter() {
        metricsCollector.markEventsReceived(5);

        assertThatCounterEquals("nakadi_events_received", 5.0);
    }

    @Test
    public void shouldIncrementErrorsCounter() {
        metricsCollector.markErrorWhileConsuming();

        assertThatCounterEquals("nakadi_errors_while_consuming", 1.0);
    }

    @Test
    public void shouldIncrementReconnectionsCounter() {
        metricsCollector.markReconnection();

        assertThatCounterEquals("nakadi_reconnections", 1.0);
    }

    @Test
    public void shouldIncrementSuccessfullyProcessedCounter() {
        metricsCollector.markMessageSuccessfullyProcessed();

        assertThatCounterEquals("nakadi_messages_successfully_processed", 1.0);
    }

    private void assertThatCounterEquals(String counterName, double value) {
        Counter counter = meterRegistry.counter(counterName, Collections.singleton(EVENT_TYPE_TEST_EVENT));

        assertThat(counter.count(), equalTo(value));
    }
}