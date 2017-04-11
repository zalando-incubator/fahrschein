package org.zalando.fahrschein;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.zalando.fahrschein.domain.*;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.*;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.zalando.fahrschein.Preconditions.checkState;

class NakadiReader<T> implements IORunnable {

    private static final Logger LOG = LoggerFactory.getLogger(NakadiReader.class);
    private static final TypeReference<Collection<Cursor>> COLLECTION_OF_CURSORS = new TypeReference<Collection<Cursor>>() {
    };

    private final URI uri;
    private final ClientHttpRequestFactory clientHttpRequestFactory;
    private final BackoffStrategy backoffStrategy;
    private final CursorManager cursorManager;

    private final Set<String> eventNames;
    private final Optional<Subscription> subscription;
    private final Optional<Lock> lock;
    private final Class<T> eventClass;
    private final Listener<T> listener;
    private final ErrorHandler errorHandler;

    private final JsonFactory jsonFactory;
    private final ObjectReader eventReader;
    private final ObjectWriter cursorHeaderWriter;
    private final ReaderManager readerManager;

    private final MetricsCollector metricsCollector;

    NakadiReader(URI uri, ClientHttpRequestFactory clientHttpRequestFactory, BackoffStrategy backoffStrategy, CursorManager cursorManager, ObjectMapper objectMapper, Set<String> eventNames, Optional<Subscription> subscription, Optional<Lock> lock, Class<T> eventClass, Listener<T> listener, ErrorHandler errorHandler, ReaderManager readerManager, final MetricsCollector metricsCollector) {

        checkState(subscription.isPresent() || eventNames.size() == 1, "Low level api only supports reading from a single event");

        this.uri = uri;
        this.clientHttpRequestFactory = clientHttpRequestFactory;
        this.backoffStrategy = backoffStrategy;
        this.cursorManager = cursorManager;
        this.eventNames = eventNames;
        this.subscription = subscription;
        this.lock = lock;
        this.eventClass = eventClass;
        this.listener = listener;
        this.errorHandler = errorHandler;
        this.readerManager = readerManager;
        this.metricsCollector = metricsCollector;

        this.jsonFactory = objectMapper.getFactory();
        this.eventReader = objectMapper.reader().forType(eventClass);
        this.cursorHeaderWriter = DefaultObjectMapper.INSTANCE.writerFor(COLLECTION_OF_CURSORS);
    }

    static class JsonInput implements Closeable {
        private final JsonFactory jsonFactory;
        private final ClientHttpResponse response;
        private JsonParser jsonParser;

        JsonInput(JsonFactory jsonFactory, ClientHttpResponse response) {
            this.jsonFactory = jsonFactory;
            this.response = response;
        }

        ClientHttpResponse getResponse() {
            return response;
        }

        JsonParser getJsonParser() throws IOException {
            if (jsonParser == null) {
                jsonParser = jsonFactory.createParser(response.getBody()).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            }
            return jsonParser;
        }

        @Override
        public void close() {
            try {
                if (jsonParser != null) {
                    try {
                        LOG.trace("Trying to close json parser");
                        jsonParser.close();
                        LOG.trace("Closed json parser");
                    } catch (IOException e) {
                        LOG.warn("Could not close json parser", e);
                    }
                }
            } finally {
                LOG.trace("Trying to close response");
                response.close();
                LOG.trace("Closed response");
            }
        }
    }

    private static Optional<String> getStreamId(ClientHttpResponse response) {
        final HttpHeaders headers = response.getHeaders();
        final String streamId = headers == null ? null : headers.getFirst("X-Nakadi-StreamId");
        return Optional.ofNullable(streamId);
    }

    private JsonInput openJsonInput() throws IOException {
        final String cursorsHeader = getCursorsHeader();
        final ClientHttpRequest request = clientHttpRequestFactory.createRequest(uri, HttpMethod.GET);
        if (cursorsHeader != null) {
            request.getHeaders().put("X-Nakadi-Cursors", singletonList(cursorsHeader));
        }
        final ClientHttpResponse response = request.execute();
        try {
            final Optional<String> streamId = getStreamId(response);

            if (subscription.isPresent() && streamId.isPresent()) {
                cursorManager.addStreamId(subscription.get(), streamId.get());
            }

            return new JsonInput(jsonFactory, response);
        } catch (Throwable throwable) {
            try {
                response.close();
            } catch (Throwable suppressed) {
                throwable.addSuppressed(suppressed);
            }
            throw throwable;
        }
    }

    @Nullable
    private String getCursorsHeader() throws IOException {
        if (!subscription.isPresent()) {
            final Collection<Cursor> lockedCursors = getLockedCursors();

            if (!lockedCursors.isEmpty()) {
                return cursorHeaderWriter.writeValueAsString(lockedCursors);
            }
        }
        return null;
    }

    private Collection<Cursor> getLockedCursors() throws IOException {
        final Collection<Cursor> cursors = cursorManager.getCursors(eventNames.iterator().next());
        if (lock.isPresent()) {
            final Map<String, String> offsets = cursors.stream().collect(toMap(Cursor::getPartition, Cursor::getOffset));
            final List<Partition> partitions = lock.get().getPartitions();
            return partitions.stream().map(partition -> new Cursor(partition.getPartition(), offsets.getOrDefault(partition.getPartition(), "BEGIN"))).collect(toList());
        } else {
            return cursors;
        }
    }

    private String getCurrentEventName(Cursor cursor) {
        final String eventName = cursor.getEventType();
        return eventName != null ? eventName : eventNames.iterator().next();
    }

    private void processBatch(Batch<T> batch) throws IOException {
        final Cursor cursor = batch.getCursor();
        final String eventName = getCurrentEventName(cursor);
        try {
            listener.accept(batch.getEvents());
            cursorManager.onSuccess(eventName, cursor);
        } catch (EventAlreadyProcessedException e) {
            LOG.info("Events for [{}] partition [{}] at offset [{}] were already processed", eventName, cursor.getPartition(), cursor.getOffset());
        } catch (Throwable throwable) {
            LOG.warn("Exception while processing events for [{}] on partition [{}] at offset [{}]", eventName, cursor.getPartition(), cursor.getOffset(), throwable);

            throw throwable;
        }
    }

    private Cursor readCursor(JsonParser jsonParser) throws IOException {
        String partition = null;
        String offset = null;
        String eventType = null;
        String cursorToken = null;

        expectToken(jsonParser, JsonToken.START_OBJECT);

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String field = jsonParser.getCurrentName();
            switch (field) {
                case "partition":
                    partition = jsonParser.nextTextValue();
                    break;
                case "offset":
                    offset = jsonParser.nextTextValue();
                    break;
                case "event_type":
                    eventType = jsonParser.nextTextValue();
                    break;
                case "cursor_token":
                    cursorToken = jsonParser.nextTextValue();
                    break;
                default:
                    LOG.warn("Unexpected field [{}] in cursor", field);
                    jsonParser.nextToken();
                    jsonParser.skipChildren();
                    break;
            }
        }

        if (partition == null) {
            throw new IllegalStateException("Could not read partition from cursor");
        }
        if (offset == null) {
            throw new IllegalStateException("Could not read offset from cursor for partition [" + partition + "]");
        }

        return new Cursor(partition, offset, eventType, cursorToken);
    }

    private List<T> readEvents(final JsonParser jsonParser) throws IOException {
        expectToken(jsonParser, JsonToken.START_ARRAY);
        jsonParser.clearCurrentToken();

        final Iterator<T> eventIterator = eventReader.readValues(jsonParser, eventClass);

        final List<T> events = new ArrayList<>();
        while (true) {
            try {
                // MappingIterator#hasNext can theoretically also throw RuntimeExceptions, that's why we use this strange loop structure
                if (eventIterator.hasNext()) {
                    events.add(eventClass.cast(eventIterator.next()));
                } else {
                    break;
                }
            } catch (RuntimeException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof JsonMappingException) {
                    errorHandler.onMappingException((JsonMappingException) cause);
                } else if (cause instanceof IOException) {
                    throw (IOException)cause;
                } else {
                    throw e;
                }
            }
        }
        return events;
    }

    @Override
    public void run() throws IOException {
        try {
            runInternal();
        } catch (BackoffException e) {
            throw e.getCause();
        }
    }

    /*
     * @VisibleForTesting
     */
    void runInternal() throws IOException, BackoffException {
        LOG.info("Starting to listen for events for {}", eventNames);

        JsonInput jsonInput = openJsonInput();
        boolean jsonInputIsClosed = false;

        int errorCount = 0;

    while (true) {
      try {

        verifyTerminationIsNotRequested();

        if (readerManager.discontinueReading(eventNames, subscription)) {
          stopReadingAndSleep(jsonInput);
          jsonInputIsClosed = true;
        } else {

          if (jsonInputIsClosed) {
            jsonInput = openJsonInput();
          }

          final JsonParser jsonParser = jsonInput.getJsonParser();

          readBatch(jsonParser);

          errorCount = 0;
        }
      } catch (IOException e) {

        metricsCollector.markErrorWhileConsuming();

        if (errorCount > 0) {
          LOG.warn("Got [{}] [{}] while reading events for {} after [{}] retries", e.getClass()
                                                                                    .getSimpleName(), e.getMessage(), eventNames, errorCount, e);
        } else {
          LOG.info("Got [{}] [{}] while reading events for {}", e.getClass()
                                                                 .getSimpleName(), e.getMessage(), eventNames, e);
        }

        jsonInput.close();

        if (Thread.currentThread()
                  .isInterrupted()) {
          LOG.warn("Thread was interrupted");
          break;
        }

        try {
          LOG.debug("Reconnecting after [{}] errors", errorCount);
          jsonInput = backoffStrategy.call(errorCount, e, this::openJsonInput);
          LOG.info("Reconnected after [{}] errors", errorCount);
          metricsCollector.markReconnection();
        } catch (InterruptedException interruptedException) {
          LOG.warn("Interrupted during reconnection", interruptedException);

          Thread.currentThread()
                .interrupt();
          return;
        }

        errorCount++;
      } catch (Throwable e) {
        try {
          jsonInput.close();
        } catch (Throwable suppressed) {
          e.addSuppressed(suppressed);
        }
        throw e;
      }
    }
    jsonInput.close();
  }

    private void stopReadingAndSleep(JsonInput jsonInput) {
        jsonInput.close();
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          LOG.debug("Sleeping during discontinued reading interrupted", e);
        }
    }

    private void verifyTerminationIsNotRequested() throws InterruptedIOException {
        if (readerManager.terminateReader(eventNames, subscription)) {
            throw new ReadingTerminatedException();
        }

        if (Thread.currentThread()
                  .isInterrupted()) {
            throw new InterruptedIOException("Interrupted");
        }
    }

    /*
     * @VisibleForTesting
     */
    void readSingleBatch() throws IOException {
        try (final JsonInput jsonInput = openJsonInput()) {
            final JsonParser jsonParser = jsonInput.getJsonParser();
            readBatch(jsonParser);
        } catch (IOException e) {
            metricsCollector.markErrorWhileConsuming();
            throw e;
        }
    }

    private void readBatch(final JsonParser jsonParser) throws IOException {
        LOG.debug("Waiting for next batch of events for {}", eventNames);

        expectToken(jsonParser, JsonToken.START_OBJECT);
        metricsCollector.markMessageReceived();

        Cursor cursor = null;
        List<T> events = null;

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            final String field = jsonParser.getCurrentName();
            switch (field) {
                case "cursor": {
                    cursor = readCursor(jsonParser);
                    break;
                }
                case "events": {
                    events = readEvents(jsonParser);
                    break;
                }
                case "info": {
                    LOG.debug("Skipping stream info in event batch");
                    jsonParser.nextToken();
                    jsonParser.skipChildren();
                    break;
                }
                default: {
                    LOG.warn("Unexpected field [{}] in event batch", field);
                    jsonParser.nextToken();
                    jsonParser.skipChildren();
                    break;
                }
            }
        }

        if (cursor == null) {
            throw new IOException("Could not read cursor");
        }

        final String eventName = getCurrentEventName(cursor);
        LOG.debug("Cursor for [{}] partition [{}] at offset [{}]", eventName, cursor.getPartition(), cursor.getOffset());

        if (events == null) {
            metricsCollector.markEventsReceived(0);
        } else {
            metricsCollector.markEventsReceived(events.size());

            final Batch<T> batch = new Batch<>(cursor, Collections.unmodifiableList(events));

            processBatch(batch);

            metricsCollector.markMessageSuccessfullyProcessed();
        }
    }

    private void expectToken(JsonParser jsonParser, JsonToken expectedToken) throws IOException {
        final JsonToken token = jsonParser.nextToken();
        verifyTerminationIsNotRequested();
        if (token == null) {
            throw new EOFException("Stream was closed");
        }
        if (token != expectedToken) {
            throw new IOException(String.format("Expected [%s] but got [%s]", expectedToken, token));
        }
    }

}
