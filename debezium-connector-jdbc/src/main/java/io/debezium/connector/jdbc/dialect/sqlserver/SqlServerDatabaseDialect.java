/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc.dialect.sqlserver;

import java.util.Optional;

import org.hibernate.SessionFactory;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.SQLServerDialect;

import io.debezium.connector.jdbc.JdbcSinkConnectorConfig;
import io.debezium.connector.jdbc.JdbcSinkRecord;
import io.debezium.connector.jdbc.dialect.DatabaseDialect;
import io.debezium.connector.jdbc.dialect.DatabaseDialectProvider;
import io.debezium.connector.jdbc.dialect.GeneralDatabaseDialect;
import io.debezium.connector.jdbc.dialect.SqlStatementBuilder;
import io.debezium.connector.jdbc.dialect.sqlserver.connect.ConnectTimeType;
import io.debezium.connector.jdbc.relational.TableDescriptor;

/**
 * A {@link DatabaseDialect} implementation for SQL Server.
 *
 * @author Chris Cranford
 */
public class SqlServerDatabaseDialect extends GeneralDatabaseDialect {

    public static class SqlServerDatabaseDialectProvider implements DatabaseDialectProvider {
        @Override
        public boolean supports(Dialect dialect) {
            return dialect instanceof SQLServerDialect;
        }

        @Override
        public Class<?> name() {
            return SqlServerDatabaseDialect.class;
        }

        @Override
        public DatabaseDialect instantiate(JdbcSinkConnectorConfig config, SessionFactory sessionFactory) {
            return new SqlServerDatabaseDialect(config, sessionFactory);
        }
    }

    private SqlServerDatabaseDialect(JdbcSinkConnectorConfig config, SessionFactory sessionFactory) {
        super(config, sessionFactory);
    }

    @Override
    public String getInsertStatement(TableDescriptor table, JdbcSinkRecord record) {
        String insertStatement = super.getInsertStatement(table, record);
        return wrapWithIdentityInsert(table, insertStatement);
    }

    @Override
    public String getAlterTablePrefix() {
        return "ADD ";
    }

    @Override
    public String getAlterTableSuffix() {
        return "";
    }

    private String wrapWithIdentityInsert(TableDescriptor table, String sqlStatement) {

        if (!table.hasAutoGeneratedIdentityColumn() || !getConfig().isSqlServerIdentityInsert()) {
            return sqlStatement;
        }

        String qualifiedTableName = getQualifiedTableName(table.getId());
        return new StringBuilder()
                .append("SET IDENTITY_INSERT ").append(qualifiedTableName).append(" ON ;")
                .append(sqlStatement).append(";")
                .append("SET IDENTITY_INSERT ").append(qualifiedTableName).append(" OFF ;")
                .toString();

    }

    @Override
    protected Optional<String> getDatabaseTimeZoneQuery() {
        return Optional.of("SELECT CURRENT_TIMEZONE()");
    }

    @Override
    protected void registerTypes() {
        super.registerTypes();

        registerType(BitType.INSTANCE);
        registerType(BytesType.INSTANCE);
        registerType(XmlType.INSTANCE);
        registerType(ZonedTimeType.INSTANCE);
        registerType(ConnectTimeType.INSTANCE);
    }

    @Override
    public String getTimeQueryBinding() {
        return "cast(? as time(7))";
    }

    @Override
    public int getMaxVarcharLengthInKey() {
        return 900;
    }

    @Override
    public int getMaxTimePrecision() {
        return 7;
    }

    @Override
    public int getMaxTimestampPrecision() {
        return 7;
    }

    @Override
    public String getTimestampPositiveInfinityValue() {
        return "9999-12-31T23:59:59+00:00";
    }

    @Override
    public String getTimestampNegativeInfinityValue() {
        return "0001-01-01T00:00:00+00:00";
    }

    @Override
    public String getUpsertStatement(TableDescriptor table, JdbcSinkRecord record) {
        final SqlStatementBuilder builder = new SqlStatementBuilder();
        builder.append("MERGE INTO ");
        builder.append(getQualifiedTableName(table.getId()));
        builder.append(" WITH (HOLDLOCK) AS TARGET USING (SELECT ");
        builder.appendLists(", ", record.keyFieldNames(), record.nonKeyFieldNames(),
                (name) -> columnNameFromField(name, columnQueryBindingFromField(name, table, record) + " AS ", record));
        builder.append(") AS INCOMING ON (");
        builder.appendList(" AND ", record.keyFieldNames(), (name) -> {
            final String columnName = columnNameFromField(name, record);
            return "TARGET." + columnName + "=INCOMING." + columnName;
        });
        builder.append(")");

        if (!record.nonKeyFieldNames().isEmpty()) {
            builder.append(" WHEN MATCHED THEN UPDATE SET ");
            builder.appendList(",", record.nonKeyFieldNames(), (name) -> {
                final String columnName = columnNameFromField(name, record);
                return columnName + "=INCOMING." + columnName;
            });
        }

        builder.append(" WHEN NOT MATCHED THEN INSERT (");
        builder.appendLists(", ", record.nonKeyFieldNames(), record.keyFieldNames(), (name) -> columnNameFromField(name, record));
        builder.append(") VALUES (");
        builder.appendLists(",", record.nonKeyFieldNames(), record.keyFieldNames(), (name) -> columnNameFromField(name, "INCOMING.", record));
        builder.append(")");
        builder.append(";"); // SQL server requires this to be terminated this way.

        return wrapWithIdentityInsert(table, builder.build());
    }

    @Override
    public String getByteArrayFormat() {
        return "CONVERT(VARBINARY, '0x%s')";
    }

}
