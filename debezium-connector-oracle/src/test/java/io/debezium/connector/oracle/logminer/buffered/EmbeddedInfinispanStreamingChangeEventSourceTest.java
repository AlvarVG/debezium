/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer.buffered;

import io.debezium.config.Configuration;
import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.OracleConnectorConfig.LogMiningBufferType;
import io.debezium.connector.oracle.junit.SkipWhenAdapterNameIsNot;
import io.debezium.connector.oracle.util.TestHelper;

/**
 * @author Chris Cranford
 */
@SkipWhenAdapterNameIsNot(value = SkipWhenAdapterNameIsNot.AdapterName.LOGMINER_BUFFERED)
public class EmbeddedInfinispanStreamingChangeEventSourceTest extends AbstractBufferedLogMinerStreamingChangeEventSourceTest {

    @Override
    protected Configuration.Builder getConfig() {
        final LogMiningBufferType bufferType = LogMiningBufferType.INFINISPAN_EMBEDDED;

        String globalConfiguration = "<?xml version=\"1.0\"?>" +
                "<infinispan>" +
                "    <threads>" +
                "        <blocking-bounded-queue-thread-pool max-threads=\"10\" name=\"myexec\" keepalive-time=\"10000\" queue-length=\"5000\"/>" +
                "    </threads>" +
                "    <cache-container blocking-executor=\"myexec\"/>" +
                "</infinispan>";

        return TestHelper.withDefaultInfinispanCacheConfigurations(bufferType,
                TestHelper.defaultConfig()
                        .with(OracleConnectorConfig.LOG_MINING_BUFFER_INFINISPAN_CACHE_GLOBAL, globalConfiguration)
                        .with(OracleConnectorConfig.LOG_MINING_BUFFER_TYPE, bufferType)
                        .with(OracleConnectorConfig.LOG_MINING_BUFFER_DROP_ON_STOP, true));
    }

    @Override
    protected boolean isTransactionAbandonmentSupported() {
        return false;
    }

}
