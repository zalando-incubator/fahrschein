package org.zalando.fahrschein.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.zalando.fahrschein.MetricsCollector;

public class MicrometerLastActivityMetricsCollector implements MetricsCollector {

    private Instant lastMessageReceived = Instant.MIN;
    private Instant lastMessageSuccessfullyProcessed = Instant.MIN;
    private Instant lastEventReceived = Instant.MIN;
    private Instant lastErrorHappened = Instant.MIN;
    private Instant lastReconnect = Instant.MIN;

    private final Clock clock;

    public MicrometerLastActivityMetricsCollector(final MeterRegistry meterRegistry, final Iterable<Tag> tags) {
        this(meterRegistry, tags, Clock.systemDefaultZone());
    }

    public MicrometerLastActivityMetricsCollector(final MeterRegistry meterRegistry, final Iterable<Tag> tags,
          final Clock clock) {

        this.clock = clock;

        Gauge.builder("nakadi_last_message_received",
              () -> Duration.between(lastMessageReceived, Instant.now(this.clock)).toMillis())
              .baseUnit("ms")
              .tags(tags)
              .register(meterRegistry);

        Gauge.builder("nakadi_last_message_successfully_processed",
              () -> Duration.between(lastMessageSuccessfullyProcessed, Instant.now(this.clock)).toMillis())
              .baseUnit("ms")
              .tags(tags)
              .register(meterRegistry);

        Gauge.builder("nakadi_last_event_received",
              () -> Duration.between(lastEventReceived, Instant.now(this.clock)).toMillis())
              .baseUnit("ms")
              .tags(tags)
              .register(meterRegistry);

        Gauge.builder("nakadi_last_error_happened",
              () -> Duration.between(lastErrorHappened, Instant.now(this.clock)).toMillis())
              .baseUnit("ms")
              .tags(tags)
              .register(meterRegistry);

        Gauge.builder("nakadi_last_reconnect",
              () -> Duration.between(lastReconnect, Instant.now(this.clock)).toMillis())
              .baseUnit("ms")
              .tags(tags)
              .register(meterRegistry);

    }

    @Override
    public void markMessageReceived() {
        lastMessageReceived = Instant.now(clock);
    }

    @Override
    public void markEventsReceived(final int size) {
        lastEventReceived = Instant.now(clock);
    }

    @Override
    public void markErrorWhileConsuming() {
        lastErrorHappened = Instant.now(clock);
    }

    @Override
    public void markReconnection() {
        lastReconnect = Instant.now(clock);
    }

    @Override
    public void markMessageSuccessfullyProcessed() {
        lastMessageSuccessfullyProcessed = Instant.now(clock);
    }
}
