package org.zalando.fahrschein.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.zalando.fahrschein.MetricsCollector;

public class MicrometerMetricsCollector implements MetricsCollector {
    private final Counter messagesReceived;
    private final Counter eventsReceived;
    private final Counter errorsWhileConsuming;
    private final Counter reconnections;
    private final Counter messagesSuccessfullyProcessed;

    public MicrometerMetricsCollector(final MeterRegistry meterRegistry, final Iterable<Tag> tags) {
        messagesReceived = Counter.builder("nakadi_messages_received").tags(tags)
              .register(meterRegistry);

        eventsReceived = Counter.builder("nakadi_events_received").tags(tags)
              .register(meterRegistry);

        errorsWhileConsuming = Counter.builder("nakadi_errors_while_consuming").tags(tags)
              .register(meterRegistry);

        reconnections = Counter.builder("nakadi_reconnections").tags(tags)
              .register(meterRegistry);

        messagesSuccessfullyProcessed = Counter.builder("nakadi_messages_successfully_processed").tags(tags)
              .register(meterRegistry);
    }

    @Override
    public void markMessageReceived() {
        messagesReceived.increment();
    }

    @Override
    public void markEventsReceived(final int size) {
        eventsReceived.increment(size);
    }

    @Override
    public void markErrorWhileConsuming() {
        errorsWhileConsuming.increment();
    }

    @Override
    public void markReconnection() {
        reconnections.increment();
    }

    @Override
    public void markMessageSuccessfullyProcessed() {
        messagesSuccessfullyProcessed.increment();
    }
}
