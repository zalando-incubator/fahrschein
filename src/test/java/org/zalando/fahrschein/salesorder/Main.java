package org.zalando.fahrschein.salesorder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zalando.fahrschein.EventProcessingException;
import org.zalando.fahrschein.ExponentialBackoffStrategy;
import org.zalando.fahrschein.IORunnable;
import org.zalando.fahrschein.InMemoryCursorManager;
import org.zalando.fahrschein.Listener;
import org.zalando.fahrschein.NakadiClient;
import org.zalando.fahrschein.NoBackoffStrategy;
import org.zalando.fahrschein.ZignAccessTokenProvider;
import org.zalando.fahrschein.domain.Lock;
import org.zalando.fahrschein.domain.Partition;
import org.zalando.fahrschein.jdbc.JdbcCursorManager;
import org.zalando.fahrschein.jdbc.JdbcPartitionManager;
import org.zalando.fahrschein.salesorder.domain.SalesOrderPlaced;
import org.zalando.jackson.datatype.money.MoneyModule;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final String SALES_ORDER_SERVICE_ORDER_PLACED = "sales-order-service.order-placed";
    private static final URI NAKADI_URI = URI.create("https://nakadi-staging.aruha-test.zalan.do");
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/local_nakadi_cursor_db";
    private static final String JDBC_USERNAME = "postgres";
    private static final String JDBC_PASSWORD = "postgres";
    public static final int INSTANCES = 4;

    public static void main(String[] args) throws IOException, InterruptedException {

        final ObjectMapper objectMapper = new ObjectMapper();

        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.disable(MapperFeature.DEFAULT_VIEW_INCLUSION);

        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new MoneyModule());
        objectMapper.registerModule(new GuavaModule());
        objectMapper.registerModule(new ParameterNamesModule());

        AtomicInteger counter = new AtomicInteger();

        final Listener<SalesOrderPlaced> listener = events -> {
            if (Math.random() < 0.0001) {
                // For testing reconnection logic
                throw new EventProcessingException("Random failure");
            } else {
                for (SalesOrderPlaced salesOrderPlaced : events) {
                    LOG.debug("Received sales order [{}]", salesOrderPlaced.getSalesOrder().getOrderNumber());
                    final int count = counter.incrementAndGet();
                    if (count % 100 == 0) {
                        LOG.info("Received [{}] sales orders", count);
                    }
                }
            }
        };

        //simpleListen(objectMapper, listener);

        //persistentListen(objectMapper, listener);

        multiInstanceListen(objectMapper, listener);
    }

    private static void simpleListen(ObjectMapper objectMapper, Listener<SalesOrderPlaced> listener) throws IOException {
        final InMemoryCursorManager cursorManager = new InMemoryCursorManager();

        final NakadiClient nakadiClient = NakadiClient.builder(NAKADI_URI)
                .withAccessTokenProvider(new ZignAccessTokenProvider())
                .withCursorManager(cursorManager)
                .build();

        final List<Partition> partitions = nakadiClient.getPartitions(SALES_ORDER_SERVICE_ORDER_PLACED);
        cursorManager.fromOldestAvailableOffset(SALES_ORDER_SERVICE_ORDER_PLACED, partitions);

        nakadiClient.stream(SALES_ORDER_SERVICE_ORDER_PLACED)
                .withObjectMapper(objectMapper)
                .withBackoffStrategy(new ExponentialBackoffStrategy().withMaxRetries(10))
                .listen(SalesOrderPlaced.class, listener);
    }

    private static void persistentListen(ObjectMapper objectMapper, Listener<SalesOrderPlaced> listener) throws IOException {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(JDBC_URL);
        hikariConfig.setUsername(JDBC_USERNAME);
        hikariConfig.setPassword(JDBC_PASSWORD);

        final DataSource dataSource = new HikariDataSource(hikariConfig);

        final JdbcCursorManager cursorManager = new JdbcCursorManager(dataSource, "fahrschein-demo");

        final NakadiClient nakadiClient = NakadiClient.builder(NAKADI_URI)
                .withAccessTokenProvider(new ZignAccessTokenProvider())
                .withCursorManager(cursorManager)
                .build();

        final List<Partition> partitions = nakadiClient.getPartitions(SALES_ORDER_SERVICE_ORDER_PLACED);
        cursorManager.fromOldestAvailableOffset(SALES_ORDER_SERVICE_ORDER_PLACED, partitions);

        nakadiClient.stream(SALES_ORDER_SERVICE_ORDER_PLACED)
                .withObjectMapper(objectMapper)
                .listen(SalesOrderPlaced.class, listener);
    }

    private static void multiInstanceListen(ObjectMapper objectMapper, Listener<SalesOrderPlaced> listener) throws IOException {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(JDBC_URL);
        hikariConfig.setUsername(JDBC_USERNAME);
        hikariConfig.setPassword(JDBC_PASSWORD);

        final DataSource dataSource = new HikariDataSource(hikariConfig);

        final ZignAccessTokenProvider accessTokenProvider = new ZignAccessTokenProvider();

        final AtomicInteger name = new AtomicInteger();
        final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(16);

        for (int i = 0; i < INSTANCES; i++) {
            final String instanceName = "consumer-" + name.getAndIncrement();
            final JdbcPartitionManager partitionManager = new JdbcPartitionManager(dataSource, "fahrschein-demo");
            final JdbcCursorManager cursorManager = new JdbcCursorManager(dataSource, "fahrschein-demo");

            final NakadiClient nakadiClient = NakadiClient.builder(NAKADI_URI)
                    .withAccessTokenProvider(accessTokenProvider)
                    .withCursorManager(cursorManager)
                    .build();

            final List<Partition> partitions = nakadiClient.getPartitions(SALES_ORDER_SERVICE_ORDER_PLACED);
            //final int partitionsPerInstance = (int) Math.ceil((double) partitions.size() / INSTANCES);
            //final List<Partition> partitionsForInstance = partitions.subList(i * partitionsPerInstance, Math.min(partitions.size() + 1, (i + 1) * partitionsPerInstance));

            cursorManager.fromOldestAvailableOffset(SALES_ORDER_SERVICE_ORDER_PLACED, partitions);

            final IORunnable runnable = () -> {
                final Optional<Lock> optionalLock = partitionManager.lockPartitions(SALES_ORDER_SERVICE_ORDER_PLACED, partitions, instanceName);

                if (optionalLock.isPresent()) {
                    final Lock lock = optionalLock.get();
                    try {
                        nakadiClient.stream(SALES_ORDER_SERVICE_ORDER_PLACED)
                                .withLock(lock)
                                .withObjectMapper(objectMapper)
                                        //.withStreamParameters(new StreamParameters().withStreamLimit(1000))
                                .withBackoffStrategy(new NoBackoffStrategy())
                                .listen(SalesOrderPlaced.class, listener);
                    } catch (IOException e) {
                        LOG.error("IOException", e);
                    } finally {
                        partitionManager.unlockPartitions(lock);
                    }
                }
            };
            scheduledExecutorService.scheduleWithFixedDelay(runnable.unchecked(), 0, 1, TimeUnit.SECONDS);
        }

        while (true) {
            try {
                Thread.sleep(10L * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
