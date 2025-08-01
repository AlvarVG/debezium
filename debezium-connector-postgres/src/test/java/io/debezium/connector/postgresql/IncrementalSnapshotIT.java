/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import static io.debezium.junit.EqualityCheck.LESS_THAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.PostgresConnectorConfig.SnapshotMode;
import io.debezium.connector.postgresql.junit.SkipWhenDecoderPluginNameIsNot;
import io.debezium.connector.postgresql.junit.SkipWhenDecoderPluginNameIsNot.DecoderPluginName;
import io.debezium.converters.CloudEventsConverterTest;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.doc.FixFor;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.junit.SkipWhenDatabaseVersion;
import io.debezium.kafka.KafkaCluster;
import io.debezium.pipeline.signal.channels.KafkaSignalChannel;
import io.debezium.pipeline.source.snapshot.incremental.AbstractIncrementalSnapshotTest;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.util.Collect;
import io.debezium.util.Testing;

public class IncrementalSnapshotIT extends AbstractIncrementalSnapshotTest<PostgresConnector> {

    private static final String TOPIC_NAME = "test_server.s1.a";

    private static final String SETUP_TABLES_STMT = "DROP SCHEMA IF EXISTS s1 CASCADE;" + "CREATE SCHEMA s1; "
            + "CREATE SCHEMA s2; " + "CREATE TABLE s1.a (pk SERIAL, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.b (pk SERIAL, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.a4 (pk1 integer, pk2 integer, pk3 integer, pk4 integer, aa integer, PRIMARY KEY(pk1, pk2, pk3, pk4));"
            + "CREATE TABLE s1.a42 (pk1 integer, pk2 integer, pk3 integer, pk4 integer, aa integer);"
            + "CREATE TABLE s1.anumeric (pk numeric, aa integer, PRIMARY KEY(pk));"
            + "CREATE TABLE s1.debezium_signal (id varchar(64), type varchar(32), data varchar(2048));"
            + "ALTER TABLE s1.debezium_signal REPLICA IDENTITY FULL;"
            + "CREATE TYPE enum_type AS ENUM ('UP', 'DOWN', 'LEFT', 'RIGHT', 'STORY');"
            + "CREATE TABLE s1.enumpk (pk enum_type, aa integer, PRIMARY KEY(pk));";

    @Before
    public void before() throws SQLException {
        TestHelper.dropAllSchemas();
        initializeConnectorTestFramework();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
    }

    @BeforeClass
    public static void startKafka() throws Exception {
        File dataDir = Testing.Files.createTestingDirectory("signal_cluster");
        Testing.Files.delete(dataDir);
        kafka = new KafkaCluster().usingDirectory(dataDir)
                .deleteDataPriorToStartup(true)
                .deleteDataUponShutdown(true)
                .addBrokers(1)
                .withKafkaConfiguration(Collect.propertiesOf(
                        "auto.create.topics.enable", "false",
                        "zookeeper.session.timeout.ms", "20000"))
                .startup();

        kafka.createTopic("signals_topic", 1, 1);
    }

    @AfterClass
    public static void stopKafka() {
        if (kafka != null) {
            kafka.shutdown();
        }
    }

    @After
    public void after() {
        stopConnector();
        TestHelper.dropDefaultReplicationSlot();
        TestHelper.dropPublication();

    }

    protected Configuration.Builder config() {
        return TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NO_DATA.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.FALSE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .with(PostgresConnectorConfig.SCHEMA_INCLUDE_LIST, "s1")
                .with(CommonConnectorConfig.SIGNAL_ENABLED_CHANNELS, "source")
                .with(CommonConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 5)
                .with(RelationalDatabaseConnectorConfig.MSG_KEY_COLUMNS, "s1.a42:pk1,pk2,pk3,pk4")
                // DBZ-4272 required to allow dropping columns just before an incremental snapshot
                .with("database.autosave", "conservative");
    }

    @Override
    protected Configuration.Builder mutableConfig(boolean signalTableOnly, boolean storeOnlyCapturedDdl) {
        final String tableIncludeList;
        if (signalTableOnly) {
            tableIncludeList = "s1.b";
        }
        else {
            tableIncludeList = "s1.a,s1.b";
        }
        return TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NO_DATA.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.FALSE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(CommonConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 5)
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .with(PostgresConnectorConfig.SCHEMA_INCLUDE_LIST, "s1")
                .with(RelationalDatabaseConnectorConfig.MSG_KEY_COLUMNS, "s1.a42:pk1,pk2,pk3,pk4")
                .with(PostgresConnectorConfig.TABLE_INCLUDE_LIST, tableIncludeList)
                // DBZ-4272 required to allow dropping columns just before an incremental snapshot
                .with("database.autosave", "conservative");
    }

    @Override
    protected Class<PostgresConnector> connectorClass() {
        return PostgresConnector.class;
    }

    @Override
    protected JdbcConnection databaseConnection() {
        return TestHelper.create();
    }

    @Override
    protected String topicName() {
        return TOPIC_NAME;
    }

    @Override
    public List<String> topicNames() {
        return List.of(TOPIC_NAME, "test_server.s1.b");
    }

    @Override
    protected String tableName() {
        return "s1.a";
    }

    @Override
    protected String noPKTopicName() {
        return "test_server.s1.a42";
    }

    @Override
    protected String noPKTableName() {
        return "s1.a42";
    }

    @Override
    protected List<String> tableNames() {
        return List.of("s1.a", "s1.b");
    }

    @Override
    protected String signalTableName() {
        return "s1.debezium_signal";
    }

    @Override
    protected void waitForConnectorToStart() {
        super.waitForConnectorToStart();
        TestHelper.waitForDefaultReplicationSlotBeActive();
    }

    @Override
    protected String connector() {
        return "postgres";
    }

    @Override
    protected String server() {
        return TestHelper.TEST_SERVER;
    }

    @Test
    @FixFor("DBZ-6481")
    public void insertsEnumPk() throws Exception {
        // Testing.Print.enable();
        final var enumValues = List.of("UP", "DOWN", "LEFT", "RIGHT", "STORY");

        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < enumValues.size(); i++) {
                connection.executeWithoutCommitting(String.format("INSERT INTO %s (%s, aa) VALUES (%s, %s)",
                        "s1.enumpk", connection.quoteIdentifier(pkFieldName()), "'" + enumValues.get(i) + "'", i));
            }
            connection.commit();
        }
        startConnector();

        sendAdHocSnapshotSignal("s1.enumpk");

        assertExpectedRecordsEnumPk(enumValues);
    }

    protected void assertExpectedRecordsEnumPk(List<String> enumValues) throws InterruptedException {
        // SNAPSHOT signal, OPEN WINDOW signal + data + CLOSE WINDOW signal
        final var records = consumeRecordsByTopic(enumValues.size() + 3).allRecordsInOrder();
        for (int i = 0; i < enumValues.size(); i++) {
            var record = records.get(i + 2);
            assertThat(((Struct) record.key()).getString("pk")).isEqualTo(enumValues.get(i));
            assertThat(((Struct) record.value()).getStruct("after").getInt32("aa")).isEqualTo(i);
        }
    }

    @Test
    public void inserts4Pks() throws Exception {
        // Testing.Print.enable();

        populate4PkTable();
        startConnector();

        sendAdHocSnapshotSignal("s1.a4");

        Thread.sleep(5000);
        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < ROW_COUNT; i++) {
                final int id = i + ROW_COUNT + 1;
                final int pk1 = id / 1000;
                final int pk2 = (id / 100) % 10;
                final int pk3 = (id / 10) % 10;
                final int pk4 = id % 10;
                connection.executeWithoutCommitting(String.format("INSERT INTO %s (pk1, pk2, pk3, pk4, aa) VALUES (%s, %s, %s, %s, %s)",
                        "s1.a4",
                        pk1,
                        pk2,
                        pk3,
                        pk4,
                        i + ROW_COUNT));
            }
            connection.commit();
        }

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> k.getInt32("pk1") * 1_000 + k.getInt32("pk2") * 100 + k.getInt32("pk3") * 10 + k.getInt32("pk4"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.a4",
                null);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    @FixFor("DBZ-7617")
    public void incrementalSnapshotMustRespectMessageKeyColumnsOrder() throws Exception {
        // Testing.Print.enable();

        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            connection.executeWithoutCommitting("INSERT INTO s1.a4 (pk1, pk2, pk3, pk4, aa) VALUES (3, 1, 1, 1, 0)");
            connection.executeWithoutCommitting("INSERT INTO s1.a4 (pk1, pk2, pk3, pk4, aa) VALUES (2, 2, 2, 2, 1)");
            connection.executeWithoutCommitting("INSERT INTO s1.a4 (pk1, pk2, pk3, pk4, aa) VALUES (1, 2, 2, 2, 2)");

            connection.commit();
        }

        startConnector(builder -> mutableConfig(false, true)
                .with(PostgresConnectorConfig.TABLE_INCLUDE_LIST, "s1.a4")
                .with(RelationalDatabaseConnectorConfig.MSG_KEY_COLUMNS, String.format("%s:%s", "s1.a4", "pk2,pk1")));

        sendAdHocSnapshotSignal("s1.a4");

        Thread.sleep(5000);

        SourceRecords sourceRecords = consumeAvailableRecordsByTopic();
        List<Integer> ordered = sourceRecords.recordsForTopic("test_server.s1.a4").stream()
                .map(sourceRecord -> ((Struct) sourceRecord.value()).getStruct("after").getInt32(valueFieldName()))
                .collect(Collectors.toList());

        assertThat(ordered).containsExactly(0, 2, 1);

    }

    @Test
    public void inserts4PksWithKafkaSignal() throws Exception {
        // Testing.Print.enable();

        populate4PkTable();
        startConnector(x -> x.with(CommonConnectorConfig.SIGNAL_ENABLED_CHANNELS, "source,kafka")
                .with(KafkaSignalChannel.SIGNAL_TOPIC, getSignalsTopic())
                .with(KafkaSignalChannel.BOOTSTRAP_SERVERS, kafka.brokerList()));

        sendExecuteSnapshotKafkaSignal("s1.a4");

        Thread.sleep(5000);
        try (JdbcConnection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < ROW_COUNT; i++) {
                final int id = i + ROW_COUNT + 1;
                final int pk1 = id / 1000;
                final int pk2 = (id / 100) % 10;
                final int pk3 = (id / 10) % 10;
                final int pk4 = id % 10;
                connection.executeWithoutCommitting(String.format("INSERT INTO %s (pk1, pk2, pk3, pk4, aa) VALUES (%s, %s, %s, %s, %s)",
                        "s1.a4",
                        pk1,
                        pk2,
                        pk3,
                        pk4,
                        i + ROW_COUNT));
            }
            connection.commit();
        }

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> k.getInt32("pk1") * 1_000 + k.getInt32("pk2") * 100 + k.getInt32("pk3") * 10 + k.getInt32("pk4"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.a4",
                null);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    public void insertsNumericPk() throws Exception {
        // Testing.Print.enable();

        try (JdbcConnection connection = databaseConnection()) {
            populateTable(connection, "s1.anumeric");
        }
        startConnector();

        sendAdHocSnapshotSignal("s1.anumeric");

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> VariableScaleDecimal.toLogical(k.getStruct("pk")).getWrappedValue().intValue(),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.anumeric",
                null);
        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
    }

    @Test
    @FixFor("DBZ-5240")
    @SkipWhenDatabaseVersion(check = LESS_THAN, major = 11, reason = "Primary keys on partitioned tables are supported only on Postgres 11+")
    public void snapshotPartitionedTable() throws Exception {

        // create partitioned table
        final String SETUP_TABLES = "CREATE TABLE s1.part (pk SERIAL, aa integer, PRIMARY KEY(pk, aa)) PARTITION BY RANGE (aa);"
                + "CREATE TABLE s1.part1 PARTITION OF s1.part FOR VALUES FROM (0) TO (500);"
                + "CREATE TABLE s1.part2 PARTITION OF s1.part FOR VALUES FROM (500) TO (1000);";
        TestHelper.execute(SETUP_TABLES);

        // insert records
        try (JdbcConnection connection = databaseConnection()) {
            populateTable(connection, "s1.part");
        }

        // start connector
        startConnector(x -> x.with(PostgresConnectorConfig.TABLE_INCLUDE_LIST, "s1.part, s1.part1, s1.part2"));
        waitForConnectorToStart();

        sendAdHocSnapshotSignal("s1.part");
        sendAdHocSnapshotSignal("s1.part1");
        sendAdHocSnapshotSignal("s1.part2");

        // check the records from the snapshot
        final int expectedRecordCount = ROW_COUNT;
        final int expectedPartRecordCount = ROW_COUNT / 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(
                expectedRecordCount,
                x -> true,
                k -> k.getInt32("pk"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.part",
                null);
        final Map<Integer, Integer> dbChangesPart1 = consumeMixedWithIncrementalSnapshot(
                expectedPartRecordCount,
                x -> true,
                k -> k.getInt32("pk"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.part1",
                null);
        final Map<Integer, Integer> dbChangesPart2 = consumeMixedWithIncrementalSnapshot(
                expectedPartRecordCount,
                x -> true,
                k -> k.getInt32("pk"),
                record -> ((Struct) record.value()).getStruct("after").getInt32(valueFieldName()),
                "test_server.s1.part2",
                null);

        for (int i = 0; i < expectedRecordCount; i++) {
            assertThat(dbChanges).contains(entry(i + 1, i));
        }
        for (int i = 0; i < expectedPartRecordCount; i++) {
            assertThat(dbChangesPart1).contains(entry(i + 1, i));
            assertThat(dbChangesPart2).contains(entry(i + 1 + expectedPartRecordCount, i + expectedPartRecordCount));
        }
    }

    @Test
    @FixFor("DBZ-4329")
    public void obsoleteSourceInfoIsExcludedFromRecord() throws Exception {
        populateTable();
        startConnector();

        sendAdHocSnapshotSignal();

        final Map<Integer, Struct> dbChanges = consumeMixedWithIncrementalSnapshot(
                ROW_COUNT,
                record -> ((Struct) record.value()).getStruct("source"),
                x -> true,
                null,
                topicName());
        Set<Map.Entry<Integer, Struct>> entries = dbChanges.entrySet();
        assertThat(ROW_COUNT == entries.size());
        for (Map.Entry<Integer, Struct> e : entries) {
            Assert.assertTrue(e.getValue().getInt64("xmin") == null);
            Assert.assertTrue(e.getValue().getInt64("lsn") == null);
            Assert.assertTrue(e.getValue().getInt64("txId") == null);
        }
    }

    @Test
    public void shouldOutputRecordsInCloudEventsFormat() throws Exception {
        // Testing.Print.enable();

        try (JdbcConnection connection = databaseConnection()) {
            populateTable(connection, "s1.anumeric");
        }
        startConnector(x -> x.with("value.converter", "io.debezium.converters.CloudEventsConverter")
                .with("value.converter.serializer.type", "json")
                .with("value.converter.data.serializer.type", "json"));

        sendAdHocSnapshotSignal("s1.anumeric");

        final SourceRecords snapshotRecords = consumeRecordsByTopic(ROW_COUNT);
        final List<SourceRecord> snapshotTable1 = snapshotRecords.recordsForTopic("test_server.s1.anumeric");

        // test snapshot
        for (SourceRecord sourceRecord : snapshotTable1) {
            CloudEventsConverterTest.shouldConvertToCloudEventsInJson(sourceRecord, false);
        }
    }

    @Test
    @FixFor("DBZ-8150")
    @SkipWhenDecoderPluginNameIsNot(value = DecoderPluginName.PGOUTPUT, reason = "Only pgoutput ignores generated columns")
    public void ignoreGeneratedColumn() throws Exception {
        // Testing.Print.enable();

        // create table with generated column
        final String SETUP_TABLES = "CREATE TABLE s1.gencol_table (pk int, gencol varchar(10) GENERATED ALWAYS AS ('aa') STORED, aa integer, bb varchar(2), PRIMARY KEY(pk));";
        TestHelper.execute(SETUP_TABLES);

        // start connector
        startConnector(x -> x
                .with(PostgresConnectorConfig.TABLE_INCLUDE_LIST, "s1.gencol_table")
                .with(PostgresConnectorConfig.COLUMN_EXCLUDE_LIST, "s1.gencol_table.gencol"));
        waitForConnectorToStart();

        // insert record that will use schema from relation message without generated column
        try (JdbcConnection connection = databaseConnection()) {
            connection.execute("INSERT INTO s1.gencol_table (pk, aa, bb) VALUES (1, 1, 'a')");
        }

        // pgoutput plug-in does not send generated column value
        var record = consumeRecord();
        assertThat(record.valueSchema().field("gencol")).isNull();

        sendAdHocSnapshotSignal("s1.gencol_table");

        final var topicName = "test_server.s1.gencol_table";
        consumeMixedWithIncrementalSnapshot(1, topicName);

        // insert records that will use schema generated by incremental snapshot
        try (JdbcConnection connection = databaseConnection()) {
            connection.execute("INSERT INTO s1.gencol_table (pk, aa, bb) VALUES (2, 2, 'b')");
        }

        final var records = consumeRecordsByTopicUntil((cnt, r) -> r.topic().equals(topicName));
        final var data = records.recordsForTopic(topicName);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).valueSchema().field("gencol")).isNull();
    }

    protected void populate4PkTable() throws SQLException {
        try (JdbcConnection connection = databaseConnection()) {
            populate4PkTable(connection, "s1.a4");
        }
    }
}
