/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.awaitility.Awaitility;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.mongodb.MongoDbConnectorConfig.SnapshotMode;
import io.debezium.connector.mongodb.snapshot.MongoDbIncrementalSnapshotChangeEventSource;
import io.debezium.data.Envelope;
import io.debezium.doc.FixFor;
import io.debezium.engine.DebeziumEngine;
import io.debezium.junit.logging.LogInterceptor;
import io.debezium.pipeline.signal.actions.snapshotting.StopSnapshot;
import io.debezium.util.Testing;

/**
 * Test to verify incremental snapshotting for MongoDB.
 *
 * @author Jiri Pechanec
 */
public class IncrementalSnapshotIT extends AbstractMongoConnectorIT {

    protected static final int ROW_COUNT = 1_000;
    private static final int MAXIMUM_NO_RECORDS_CONSUMES = 3;

    private static final String DATABASE_NAME = "dbA";
    private static final String COLLECTION_NAME = "c1";
    private static final String COLLECTION2_NAME = "c2";
    private static final String SIGNAL_COLLECTION_NAME = DATABASE_NAME + ".signals";
    private static final String FULL_COLLECTION_NAME = DATABASE_NAME + "." + COLLECTION_NAME;
    private static final String FULL_COLLECTION2_NAME = DATABASE_NAME + "." + COLLECTION2_NAME;

    private static final String DOCUMENT_ID = "_id";

    @Before
    public void before() {
        // Set up the replication context for connections ...
        context = new MongoDbTaskContext(config().build());

        TestHelper.cleanDatabase(mongo, DATABASE_NAME);
    }

    @After
    public void after() {
        TestHelper.cleanDatabase(mongo, DATABASE_NAME);
    }

    protected Class<MongoDbConnector> connectorClass() {
        return MongoDbConnector.class;
    }

    protected Configuration.Builder config() {
        return TestHelper.getConfiguration(mongo)
                .edit()
                .with(MongoDbConnectorConfig.DATABASE_INCLUDE_LIST, DATABASE_NAME)
                .with(MongoDbConnectorConfig.COLLECTION_INCLUDE_LIST, fullDataCollectionName() + ",dbA.c1,dbA.c2")
                .with(MongoDbConnectorConfig.SIGNAL_DATA_COLLECTION, SIGNAL_COLLECTION_NAME)
                .with(MongoDbConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 5)
                .with(MongoDbConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .with(MongoDbConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NO_DATA);
    }

    protected String dataCollectionName() {
        return COLLECTION_NAME;
    }

    protected List<String> dataCollectionNames() {
        return List.of(COLLECTION_NAME, COLLECTION2_NAME);
    }

    protected String fullDataCollectionName() {
        return FULL_COLLECTION_NAME;
    }

    protected List<String> fullDataCollectionNames() {
        return List.of(FULL_COLLECTION_NAME, FULL_COLLECTION2_NAME);
    }

    protected String topicName() {
        return "mongo1" + "." + fullDataCollectionName();
    }

    protected List<String> topicNames() {
        return fullDataCollectionNames().stream().map(x -> "mongo1." + x).collect(Collectors.toList());
    }

    protected void populateDataCollection(String dataCollectionName) {
        final Document[] documents = new Document[ROW_COUNT];
        for (int i = 0; i < ROW_COUNT; i++) {
            final Document doc = new Document();
            doc.append(DOCUMENT_ID, i + 1).append("aa", i);
            documents[i] = doc;
        }
        insertDocumentsInTx(DATABASE_NAME, dataCollectionName, documents);
    }

    protected void populateDataCollection() {
        populateDataCollection(dataCollectionName());
    }

    protected void populateDataCollections() {
        for (String dataCollectionName : dataCollectionNames()) {
            populateDataCollection(dataCollectionName);
        }
    }

    protected void insertAdditionalData() {
        insertAdditionalData(COLLECTION_NAME);
    }

    protected void insertAdditionalData(String collectionName) {
        final Document[] documents = new Document[ROW_COUNT];
        for (int i = 0; i < ROW_COUNT; i++) {
            final Document doc = new Document();
            doc.append(DOCUMENT_ID, i + ROW_COUNT + 1).append("aa", i + ROW_COUNT);
            documents[i] = doc;
        }
        insertDocumentsInTx(DATABASE_NAME, collectionName, documents);
    }

    protected void updateData(int batchSize) {

        for (int i = 0; i < ROW_COUNT / batchSize; i++) {
            final Document gt = new Document();
            gt.append("$gt", i * batchSize);

            final Document lte = new Document();
            lte.append("$lte", (i + 1) * batchSize);

            final Document filter = new Document();
            filter.append("$and", Arrays.asList(
                    (new Document()).append(DOCUMENT_ID, gt),
                    (new Document()).append(DOCUMENT_ID, lte)));

            final Document update = new Document();
            update.append("$inc", (new Document()).append("aa", 2000));

            updateDocumentsInTx(DATABASE_NAME, COLLECTION_NAME, filter, update);
        }
    }

    protected void startConnector(Function<Configuration.Builder, Configuration.Builder> custConfig) {
        startConnector(custConfig, loggingCompletion());
    }

    protected void startConnector(Function<Configuration.Builder, Configuration.Builder> custConfig, DebeziumEngine.CompletionCallback callback) {
        final Configuration config = custConfig.apply(config()).build();
        start(connectorClass(), config, callback);
        waitForConnectorToStart();

        waitForAvailableRecords(5, TimeUnit.SECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();
    }

    protected void startConnector() {
        startConnector(Function.identity(), loggingCompletion());
    }

    protected void waitForConnectorToStart() {
        assertConnectorIsRunning();
    }

    protected void sendAdHocSnapshotSignal(String... dataCollectionIds) throws SQLException {
        final String dataCollectionIdsList = Arrays.stream(dataCollectionIds)
                .map(x -> "\\\"" + x + "\\\"")
                .collect(Collectors.joining(", "));
        insertDocuments("dbA", "signals",
                new Document[]{ Document.parse("{\"type\": \"execute-snapshot\", \"payload\": \"{\\\"data-collections\\\": [" + dataCollectionIdsList + "]}\"}") });
    }

    protected void sendAdHocSnapshotStopSignal(String... dataCollectionIds) throws SQLException {
        final String dataCollections;
        if (dataCollectionIds.length == 0) {
            dataCollections = "";
        }
        else {
            final String dataCollectionIdsList = Arrays.stream(dataCollectionIds)
                    .map(x -> "\\\"" + x + "\\\"")
                    .collect(Collectors.joining(", "));
            dataCollections = ", \\\"data-collections\\\": [" + dataCollectionIdsList + "]";
        }
        insertDocuments("dbA", "signals",
                new Document[]{ Document.parse(
                        "{\"type\": \"stop-snapshot\", \"payload\": \"{"
                                + "\\\"type\\\": \\\"INCREMENTAL\\\""
                                + dataCollections
                                + "}\"}") });
    }

    protected void sendAdHocSnapshotSignalWithAdditionalConditions(Map<String, String> additionalConditions, String... dataCollectionIds) throws SQLException {
        final String conditions = additionalConditions.entrySet().stream()
                .map(e -> String.format("{\"data-collection\": \"%s\", \"filter\": \"%s\"}", e.getKey(), e.getValue())).collect(
                        Collectors.joining(","));
        final String dataCollectionIdsList = Arrays.stream(dataCollectionIds)
                .map(x -> "\"" + x + "\"")
                .collect(Collectors.joining(", "));
        insertDocuments("dbA", "signals",
                Document.parse("{\"type\": \"execute-snapshot\", \"payload\": {"
                        + "\"type\": \"INCREMENTAL\","
                        + "\"data-collections\": [" + dataCollectionIdsList + "],"
                        + "\"additional-conditions\": [" + conditions + "]}}"));
    }

    protected void sendAdHocSnapshotSignal() throws SQLException {
        sendAdHocSnapshotSignal(fullDataCollectionName());
    }

    protected void sendPauseSignal() throws SQLException {
        insertDocuments("dbA", "signals",
                new Document[]{ Document.parse("{\"type\": \"pause-snapshot\", \"payload\": \"{}\"}") });
    }

    protected void sendResumeSignal() throws SQLException {
        insertDocuments("dbA", "signals",
                new Document[]{ Document.parse("{\"type\": \"resume-snapshot\", \"payload\": \"{}\"}") });
    }

    protected Map<Integer, Integer> consumeMixedWithIncrementalSnapshot(int recordCount) throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, topicName());
    }

    protected Map<Integer, Integer> consumeMixedWithIncrementalSnapshot(int recordCount, String topicName) throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, this::extractFieldValue, x -> true, null, topicName);
    }

    protected Integer extractFieldValue(SourceRecord record) {
        final String after = ((Struct) record.value()).getString("after");
        final Pattern p = Pattern.compile("\"" + valueFieldName() + "\": (\\d+)");
        final Matcher m = p.matcher(after);
        m.find();
        return Integer.parseInt(m.group(1));
    }

    protected <V> Map<Integer, V> consumeMixedWithIncrementalSnapshot(int recordCount, Function<SourceRecord, V> valueConverter,
                                                                      Predicate<Map.Entry<Integer, V>> dataCompleted,
                                                                      Consumer<List<SourceRecord>> recordConsumer,
                                                                      String topicName)
            throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, dataCompleted,
                k -> Integer.parseInt(k.getString(pkFieldName())), valueConverter, topicName, recordConsumer);
    }

    protected <V, K> Map<K, V> consumeMixedWithIncrementalSnapshot(int recordCount,
                                                                   Predicate<Map.Entry<K, V>> dataCompleted,
                                                                   Function<Struct, K> idCalculator,
                                                                   Function<SourceRecord, V> valueConverter,
                                                                   String topicName,
                                                                   Consumer<List<SourceRecord>> recordConsumer)
            throws InterruptedException {
        final Map<K, V> dbChanges = new HashMap<>();
        int noRecords = 0;
        for (;;) {
            final SourceRecords records = consumeRecordsByTopic(1);
            final List<SourceRecord> dataRecords = records.recordsForTopic(topicName);
            if (records.allRecordsInOrder().isEmpty()) {
                noRecords++;
                assertThat(noRecords).describedAs(String.format("Too many no data record results, %d < %d", dbChanges.size(), recordCount))
                        .isLessThanOrEqualTo(MAXIMUM_NO_RECORDS_CONSUMES);
                continue;
            }
            noRecords = 0;
            if (dataRecords == null || dataRecords.isEmpty()) {
                continue;
            }
            dataRecords.forEach(record -> {
                Testing.print(record);
                final K id = idCalculator.apply((Struct) record.key());
                final V value = valueConverter.apply(record);
                dbChanges.put(id, value);
            });
            if (recordConsumer != null) {
                recordConsumer.accept(dataRecords);
            }
            if (dbChanges.size() >= recordCount) {
                if (!dbChanges.entrySet().stream().anyMatch(dataCompleted.negate())) {
                    break;
                }
            }
        }

        assertThat(dbChanges).hasSize(recordCount);
        return dbChanges;
    }

    protected Map<Integer, SourceRecord> consumeRecordsMixedWithIncrementalSnapshot(int recordCount) throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, Function.identity(), x -> true, null, topicName());
    }

    protected Map<Integer, Integer> consumeMixedWithIncrementalSnapshot(int recordCount, Predicate<Map.Entry<Integer, Integer>> dataCompleted,
                                                                        Consumer<List<SourceRecord>> recordConsumer)
            throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, this::extractFieldValue, dataCompleted, recordConsumer, topicName());
    }

    protected Map<Integer, SourceRecord> consumeRecordsMixedWithIncrementalSnapshot(int recordCount, Predicate<Map.Entry<Integer, SourceRecord>> dataCompleted,
                                                                                    Consumer<List<SourceRecord>> recordConsumer)
            throws InterruptedException {
        return consumeMixedWithIncrementalSnapshot(recordCount, Function.identity(), dataCompleted, recordConsumer, topicName());
    }

    protected String valueFieldName() {
        return "aa";
    }

    protected String pkFieldName() {
        return "id";
    }

    private <K> void snapshotOnly(K initialId, Function<K, K> idGenerator) throws Exception {
        final Map<K, Document> documents = new LinkedHashMap<>();

        K key = initialId;
        for (int i = 0; i < ROW_COUNT; i++) {
            final Document doc = new Document();
            doc.append(DOCUMENT_ID, key).append(valueFieldName(), i);
            documents.put(key, doc);
            key = idGenerator.apply(key);
        }
        insertDocumentsInTx(DATABASE_NAME, COLLECTION_NAME, documents.values().toArray(Document[]::new));

        startConnector();
        sendAdHocSnapshotSignal();

        final var dbChanges = consumeMixedWithIncrementalSnapshot(
                ROW_COUNT,
                x -> true,
                k -> k.getString(pkFieldName()),
                this::extractFieldValue,
                topicName(), null);

        var serialization = new JsonSerialization();

        try (var connection = connect()) {
            var codecs = connection.getDatabase(DATABASE_NAME)
                    .getCollection(COLLECTION_NAME)
                    .getCodecRegistry();

            var expected = documents.values()
                    .stream()
                    .map(d -> d.toBsonDocument(BsonDocument.class, codecs))
                    .collect(toMap(
                            serialization::getDocumentId,
                            d -> d.getInt32(valueFieldName()).getValue()));

            assertThat(dbChanges).containsAllEntriesOf(expected);
        }
    }

    @Test
    public void shouldStreamWithDatabaseIncludeList() throws InterruptedException {
        startConnector(
                config -> config
                        .with(MongoDbConnectorConfig.DATABASE_INCLUDE_LIST, DATABASE_NAME)
                        .without(MongoDbConnectorConfig.COLLECTION_INCLUDE_LIST.name()),
                loggingCompletion());
        assertConnectorIsRunning();
        assertNoRecordsToConsume();
        insertDocuments(DATABASE_NAME, COLLECTION_NAME, new Document("foo", "bar"));
        SourceRecords records = consumeRecordsByTopic(1);
        records.topics().forEach(System.out::println);

        assertThat(records.topics()).contains(topicName());
        assertThat(records.recordsForTopic(topicName())).hasSize(1);
    }

    @Test
    public void snapshotOnlyInt32() throws Exception {
        snapshotOnly(0, k -> k + 1);
    }

    @Test
    public void snapshotOnlyWithInt64() throws Exception {
        long firstKey = Integer.MAX_VALUE + 1L;
        snapshotOnly(firstKey, k -> k + 1);
    }

    @Test
    public void snapshotOnlyDouble() throws Exception {
        snapshotOnly(0.0, k -> k + 1);
    }

    @Test
    public void snapshotOnlyDecimal128() throws Exception {
        Assume.assumeTrue("Decimal 128 not supported", TestHelper.decimal128Supported());
        BigDecimal firstKey = BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE);
        snapshotOnly(firstKey, k -> k.add(BigDecimal.ONE));
    }

    @Test
    public void snapshotOnlyObjectId() throws Exception {
        ObjectId firstKey = new ObjectId();
        snapshotOnly(firstKey, k -> new ObjectId());
    }

    @Test
    public void snapshotOnlyUUID() throws Exception {
        snapshotOnly(UUID.randomUUID(), k -> UUID.randomUUID());
    }

    @Test
    public void snapshotOnlyString() throws Exception {
        Supplier<String> keySupplier = () -> java.util.UUID.randomUUID().toString();
        snapshotOnly(keySupplier.get(), k -> keySupplier.get());
    }

    @Test
    public void invalidTablesInTheList() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        startConnector();

        sendAdHocSnapshotSignal("dbA.invalid1", fullDataCollectionName(), "invalid2");

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void snapshotOnlyWithRestart() throws Exception {
        // Testing.Print.enable();

        LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);

        populateDataCollection();
        final Configuration config = config().build();
        startAndConsumeTillEnd(connectorClass(), config);
        waitForConnectorToStart();

        waitForAvailableRecords(1, TimeUnit.SECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        sendAdHocSnapshotSignal();

        Awaitility.await().atMost(60, TimeUnit.SECONDS)
                .until(() -> interceptor
                        .containsMessage("No data returned by the query, incremental snapshotting of table '" + fullDataCollectionName() + "' finished"));

        final int expectedRecordCount = ROW_COUNT;
        final AtomicInteger recordCounter = new AtomicInteger();
        final AtomicBoolean restarted = new AtomicBoolean();
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount, x -> true,
                x -> {
                    if (recordCounter.addAndGet(x.size()) > 50 && !restarted.get()) {
                        stopConnector();
                        assertConnectorNotRunning();

                        start(connectorClass(), config);
                        waitForConnectorToStart();
                        restarted.set(true);
                    }
                });
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void inserts() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        startConnector();

        sendAdHocSnapshotSignal();

        insertAdditionalData();

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void updates() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        startConnector();

        sendAdHocSnapshotSignal();

        updateData(10);

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount,
                x -> x.getValue() >= 2000, null);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i + 2000));
        }
    }

    @Test
    public void updatesWithRestart() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        final Configuration config = config().build();
        startAndConsumeTillEnd(connectorClass(), config);
        waitForConnectorToStart();

        waitForAvailableRecords(1, TimeUnit.SECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        sendAdHocSnapshotSignal();

        updateData(10);

        final int expectedRecordCount = ROW_COUNT;
        final AtomicInteger recordCounter = new AtomicInteger();
        final AtomicBoolean restarted = new AtomicBoolean();
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount,
                x -> x.getValue() >= 2000, x -> {
                    if (recordCounter.addAndGet(x.size()) > 50 && !restarted.get()) {
                        stopConnector();
                        assertConnectorNotRunning();

                        start(connectorClass(), config);
                        waitForConnectorToStart();
                        restarted.set(true);
                    }
                });
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i + 2000));
        }
    }

    @Test
    public void updatesLargeChunk() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, ROW_COUNT));

        sendAdHocSnapshotSignal();

        updateData(ROW_COUNT);

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount,
                x -> x.getValue() >= 2000, null);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i + 2000));
        }
    }

    @Test
    @FixFor("DBZ-4271")
    public void stopCurrentIncrementalSnapshotWithoutCollectionsAndTakeNewNewIncrementalSnapshotAfterRestart() throws Exception {
        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);

        // We will use chunk size of 1 to have very small batches to guarantee that when we stop
        // we are still within the incremental snapshot rather than it being performed with one
        // round trip to the database
        populateDataCollection();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 1));

        // Send ad-hoc start incremental snapshot signal and wait for incremental snapshots to start
        sendAdHocSnapshotSignalAndWait();

        // stop ad-hoc snapshot without specifying any collections, canceling the entire incremental snapshot
        // This waits until we've received the stop signal.
        sendAdHocSnapshotStopSignalAndWait();

        // Consume any residual left-over events after stopping incremental snapshots such as open/close
        // and wait for the stop message in the connector logs
        assertThat(consumeAnyRemainingIncrementalSnapshotEventsAndCheckForStopMessage(
                interceptor, "Removed collections from incremental snapshot: ")).isTrue();

        // stop the connector
        stopConnector((r) -> interceptor.clear());

        // restart the connector
        // should start with no available records, should not have any incremental snapshot state
        startConnector();
        assertThat(interceptor.containsMessage("No incremental snapshot in progress")).isTrue();

        sendAdHocSnapshotSignal();

        insertAdditionalData();

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    @FixFor("DBZ-4271")
    public void stopCurrentIncrementalSnapshotWithAllCollectionsAndTakeNewNewIncrementalSnapshotAfterRestart() throws Exception {
        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);

        // We will use chunk size of 1 to have very small batches to guarantee that when we stop
        // we are still within the incremental snapshot rather than it being performed with one
        // round trip to the database
        populateDataCollection();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 1));

        // Send ad-hoc start incremental snapshot signal and wait for incremental snapshots to start
        sendAdHocSnapshotSignalAndWait();

        // stop ad-hoc snapshot without specifying any collections, canceling the entire incremental snapshot
        // This waits until we've received the stop signal.
        sendAdHocSnapshotStopSignalAndWait(fullDataCollectionName());

        // Consume any residual left-over events after stopping incremental snapshots such as open/close
        // and wait for the stop message in the connector logs
        assertThat(consumeAnyRemainingIncrementalSnapshotEventsAndCheckForStopMessage(
                interceptor, "Removing '[" + fullDataCollectionName() + "]' collections from incremental snapshot")).isTrue();

        // stop the connector
        stopConnector((r) -> interceptor.clear());

        // restart the connector
        // should start with no available records, should not have any incremental snapshot state
        startConnector();
        assertThat(interceptor.containsMessage("No incremental snapshot in progress")).isTrue();

        sendAdHocSnapshotSignal();

        insertAdditionalData();

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    @FixFor("DBZ-4271")
    public void removeNotYetCapturedCollectionFromInProgressIncrementalSnapshot() throws Exception {
        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);

        // We will use chunk size of 250, this gives us enough granularity with the incremental
        // snapshot to have a couple round trips for the first table but enough table to trigger
        // the removal of the second table before it starts being processed.
        populateDataCollections();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 250));

        final List<String> collectionIds = fullDataCollectionNames();
        assertThat(collectionIds).hasSize(2);

        final String collectionIdToRemove = collectionIds.get(1);

        // Send the start signal for all collections and stop for the second collection
        sendAdHocSnapshotSignal(collectionIds.toArray(new String[0]));
        sendAdHocSnapshotStopSignal(collectionIdToRemove);

        // Wait until the stop has been processed, verifying it was removed from the snapshot.
        Awaitility.await().atMost(60, TimeUnit.SECONDS)
                .until(() -> interceptor.containsMessage("Removing '[" + collectionIdToRemove + "]' collections from incremental snapshot"));

        insertAdditionalData(COLLECTION_NAME);

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount, topicName());
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    @FixFor("DBZ-4271")
    public void removeStartedCapturedCollectionFromInProgressIncrementalSnapshot() throws Exception {
        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);

        // We will use chunk size of 250, this gives us enough granularity with the incremental
        // snapshot to have a couple round trips for the first table but enough table to trigger
        // the removal of the second table before it starts being processed.
        populateDataCollections();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 250));

        final List<String> collectionIds = fullDataCollectionNames();
        assertThat(collectionIds).hasSize(2);

        final List<String> topicNames = topicNames();
        assertThat(topicNames).hasSize(2);

        final String collectionIdToRemove = collectionIds.get(0);

        // Send the start signal for all collections and stop for the second collection
        sendAdHocSnapshotSignal(collectionIds.toArray(new String[0]));
        sendAdHocSnapshotStopSignal(collectionIdToRemove);

        // Wait until the stop has been processed, verifying it was removed from the snapshot.
        Awaitility.await().atMost(60, TimeUnit.SECONDS)
                .until(() -> interceptor.containsMessage("Removing '[" + collectionIdToRemove + "]' collections from incremental snapshot"));

        insertAdditionalData(COLLECTION2_NAME);

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount, topicNames.get(1));
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void pauseDuringSnapshot() throws Exception {
        populateDataCollection();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 1));
        waitForConnectorToStart();

        sendAdHocSnapshotSignal();

        List<SourceRecord> records = new ArrayList<>();
        String topicName = topicName();
        consumeRecords(100, record -> {
            if (topicName.equalsIgnoreCase(record.topic())) {
                records.add(record);
            }
        });

        sendPauseSignal();

        consumeAvailableRecords(record -> {
            if (topicName.equalsIgnoreCase(record.topic())) {
                records.add(record);
            }
        });
        int beforeResume = records.size();

        sendResumeSignal();

        final int expectedRecordCount = ROW_COUNT;
        Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount - beforeResume);
        for (int i = beforeResume + 1; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void insertInsertWatermarkingStrategy() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();

        startConnector();

        waitForConnectorToStart();

        insertAdditionalData();

        sendAdHocSnapshotSignal();

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }

        assertCloseEventCount(closeEventCount -> assertThat(closeEventCount).isNotZero());

    }

    @Test
    public void insertDeleteWatermarkingStrategy() throws Exception {
        // Testing.Print.enable();

        populateDataCollection();
        startConnector(x -> x.with(CommonConnectorConfig.INCREMENTAL_SNAPSHOT_WATERMARKING_STRATEGY, "insert_delete")
                .with(CommonConnectorConfig.TOMBSTONES_ON_DELETE, false)); // Remove tombstone to avoid failure of VerifyRecord.isValid

        sendAdHocSnapshotSignal();

        insertAdditionalData();

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);

        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }

        assertCloseEventCount(closeEventCount -> assertThat(closeEventCount).isZero());
    }

    @Test
    @FixFor({ "DBZ-7138", "DBZ-9232" })
    public void executeIncrementalSnapshotWithAdditionalCondition() throws Exception {
        Testing.Print.enable();

        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);
        populateDataCollection(dataCollectionNames().get(1));
        startConnector();
        waitForConnectorToStart();

        waitForStreamingRunning("mongodb", "mongo1", getStreamingNamespace(), "0");

        sendAdHocSnapshotSignalWithAdditionalConditions(
                Map.of(fullDataCollectionNames().get(1), "{ aa: { $lt: 250 } }"),
                fullDataCollectionNames().get(1));

        final int expectedRecordCount = 250;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount, topicNames().get(1));
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }

        assertThat(interceptor.containsMessage("No data returned by the query, incremental snapshotting of table 'dbA.c2' finished")).isTrue();
        assertCloseEventCount(closeEventCount -> assertThat(closeEventCount).isNotZero());
    }

    @Test
    @FixFor("DBZ-9232")
    public void executeIncrementalSnapshotWithAdditionalCondition2() throws Exception {
        // Testing.Print.enable();

        final LogInterceptor interceptor = new LogInterceptor(MongoDbIncrementalSnapshotChangeEventSource.class);
        populateDataCollection(dataCollectionNames().get(1));
        startConnector();
        waitForConnectorToStart();

        waitForStreamingRunning("mongodb", "mongo1", getStreamingNamespace(), "0");

        sendAdHocSnapshotSignalWithAdditionalConditions(
                Map.of(fullDataCollectionNames().get(1), "{ aa: 250 }"),
                fullDataCollectionNames().get(1));

        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(1, topicNames().get(1));
        assertThat(dbChanges).contains(entry(251, 250));

        assertThat(interceptor.containsMessage("No data returned by the query, incremental snapshotting of table 'dbA.c2' finished")).isTrue();
        assertCloseEventCount(closeEventCount -> assertThat(closeEventCount).isNotZero());
    }

    private void assertCloseEventCount(Consumer<Long> consumer) {

        try (var client = TestHelper.connect(mongo)) {

            MongoDatabase db = client.getDatabase(DATABASE_NAME);

            MongoCollection<Document> collection = db.getCollection("signals");
            Document filter = new Document();
            filter.put("type", "snapshot-window-close");

            consumer.accept(collection.countDocuments(filter));
        }
    }

    protected void sendAdHocSnapshotSignalAndWait(String... collectionIds) throws Exception {
        // Sends the adhoc snapshot signal and waits for the signal event to have been received
        if (collectionIds.length == 0) {
            sendAdHocSnapshotSignal();
        }
        else {
            sendAdHocSnapshotSignal(collectionIds);
        }

        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            final AtomicBoolean result = new AtomicBoolean(false);
            consumeAvailableRecords(r -> {
                if (r.topic().endsWith(SIGNAL_COLLECTION_NAME)) {
                    result.set(true);
                }
            });
            return result.get();
        });
    }

    protected void sendAdHocSnapshotStopSignalAndWait(String... collectionIds) throws Exception {
        sendAdHocSnapshotStopSignal(collectionIds);

        // Wait for stop signal received and at least one incremental snapshot record
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            final AtomicBoolean stopSignal = new AtomicBoolean(false);
            consumeAvailableRecords(r -> {
                if (r.topic().endsWith(SIGNAL_COLLECTION_NAME)) {
                    final String after = ((Struct) r.value()).getString(Envelope.FieldName.AFTER);
                    if (after != null) {
                        final String type = Document.parse(after).getString("type");
                        if (StopSnapshot.NAME.equals(type)) {
                            stopSignal.set(true);
                        }
                    }
                }
            });
            return stopSignal.get();
        });
    }

    protected boolean consumeAnyRemainingIncrementalSnapshotEventsAndCheckForStopMessage(LogInterceptor interceptor, String stopMessage) throws Exception {
        // When an incremental snapshot is stopped, there may be some residual open/close events that
        // have been written concurrently to the signal table after the stop signal. We want to make
        // sure that those have all been read before stopping the connector.
        final AtomicBoolean stopMessageFound = new AtomicBoolean(false);
        Awaitility.await().atMost(60, TimeUnit.SECONDS)
                .pollDelay(5, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    if (interceptor.containsMessage(stopMessage)) {
                        stopMessageFound.set(true);
                    }
                    return consumeAvailableRecords(r -> {
                    }) == 0;
                });
        return stopMessageFound.get();
    }

    @Override
    protected int getMaximumEnqueuedRecordCount() {
        return ROW_COUNT * 3;
    }
}
