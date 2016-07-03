package org.zalando.fahrschein;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkState;

public class ExponentialBackoffStrategy implements BackoffStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(ExponentialBackoffStrategy.class);

    public static final int DEFAULT_INITIAL_DELAY = 500;
    public static final double DEFAULT_BACKOFF_FACTOR = 1.5;
    public static final long DEFAULT_MAX_DELAY = 10 * 60 * 1000L;

    private final int initialDelay;
    private final double backoffFactor;
    private final long maxDelay;
    private final int maxRetries;

    public ExponentialBackoffStrategy() {
        this(DEFAULT_INITIAL_DELAY, DEFAULT_BACKOFF_FACTOR, DEFAULT_MAX_DELAY, -1);
    }

    public ExponentialBackoffStrategy(int initialDelay, double backoffFactor, long maxDelay, int maxRetries) {
        checkState(initialDelay > 0, "Initial delay should be bigger than 0");
        this.initialDelay = initialDelay;
        this.backoffFactor = backoffFactor;
        this.maxDelay = maxDelay;
        this.maxRetries = maxRetries;
    }

    private long calculateDelay(double count) {
        return Math.min((long)(initialDelay*Math.pow(backoffFactor, count)), maxDelay);
    }

    private void sleepForRetries(final int count) throws InterruptedException {
        final long delay = calculateDelay(count);
        LOG.info("Retry [{}], sleeping for [{}] milliseconds", count, delay);
        Thread.sleep(delay);
    }

    private void checkMaxRetries(final IOException exception, final int count) throws BackoffException {
        if (maxRetries >= 0 && count > maxRetries) {
            LOG.info("Maximum number of retries exceeded");
            throw new BackoffException(exception);
        }
    }

    @Override
    public <T> T call(final int initialCount, final IOException initialException, final IOCallable<T> callable) throws BackoffException, InterruptedException{
        checkMaxRetries(initialException, initialCount);

        int count = initialCount;

        if (count > 0) {
            sleepForRetries(count);
        }

        while (true) {
            try {
                LOG.trace("Try [{}]", count);
                return callable.call();
            } catch (IOException e) {
                LOG.warn("Got [{}]", e.getClass().getSimpleName(), e);
                count++;

                checkMaxRetries(e, count);
                sleepForRetries(count);
            }
        }
    }
}
