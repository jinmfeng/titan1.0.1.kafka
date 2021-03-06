package com.thinkaurelius.titan.graphdb.transaction;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.ROOT_NS;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.core.schema.DefaultSchemaMaker;
import com.thinkaurelius.titan.core.TitanTransaction;
import com.thinkaurelius.titan.core.TransactionBuilder;
import com.thinkaurelius.titan.diskstorage.configuration.*;

import com.thinkaurelius.titan.diskstorage.BaseTransactionConfig;
import com.thinkaurelius.titan.diskstorage.util.StandardBaseTransactionConfig;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.database.StandardTitanGraph;
import com.thinkaurelius.titan.diskstorage.util.time.TimestampProvider;

/**
 * Used to configure a {@link com.thinkaurelius.titan.core.TitanTransaction}.
 *
 * @author Matthias Br&ouml;cheler (me@matthiasb.com);
 * @see com.thinkaurelius.titan.core.TitanTransaction
 */
public class StandardTransactionBuilder implements TransactionConfiguration, TransactionBuilder {

    private boolean isReadOnly = false;

    private boolean hasEnabledBatchLoading = false;

    private boolean assignIDsImmediately = false;

    private boolean preloadedData = false;

    private DefaultSchemaMaker defaultSchemaMaker;

    private boolean verifyExternalVertexExistence = true;

    private boolean verifyInternalVertexExistence = false;

    private boolean verifyUniqueness = true;

    private boolean acquireLocks = true;

    private boolean propertyPrefetching = true;

    private boolean singleThreaded = false;

    private boolean threadBound = false;

    private int vertexCacheSize;

    private int dirtyVertexSize;

    private long indexCacheWeight;

    private String logIdentifier;

    private int[] restrictedPartitions = new int[0];

    private Instant userCommitTime = null;

    private String groupName;

    private final boolean forceIndexUsage;

    private final ModifiableConfiguration writableCustomOptions;

    private final Configuration customOptions;

    private final StandardTitanGraph graph;

    /**
     * Constructs a new TitanTransaction configuration with default configuration parameters.
     */
    public StandardTransactionBuilder(GraphDatabaseConfiguration graphConfig, StandardTitanGraph graph) {
        Preconditions.checkNotNull(graphConfig);
        Preconditions.checkNotNull(graph);
        if (graphConfig.isReadOnly()) readOnly();
        if (graphConfig.isBatchLoading()) enableBatchLoading();
        this.graph = graph;
        this.defaultSchemaMaker = graphConfig.getDefaultSchemaMaker();
        this.assignIDsImmediately = graphConfig.hasFlushIDs();
        this.forceIndexUsage = graphConfig.hasForceIndexUsage();
        this.groupName = graphConfig.getMetricsPrefix();

        // KAFKA PRODUCER
        boolean logAll = graphConfig.getLogAllTransactions();

        if (logAll)
            this.logIdentifier = graphConfig.getAllLogTransactionName();
        else
            this.logIdentifier = null;

        this.propertyPrefetching = graphConfig.hasPropertyPrefetching();
        this.writableCustomOptions = GraphDatabaseConfiguration.buildGraphConfiguration();
        this.customOptions = new MergedConfiguration(writableCustomOptions, graphConfig.getConfiguration());
        vertexCacheSize(graphConfig.getTxVertexCacheSize());
        dirtyVertexSize(graphConfig.getTxDirtyVertexSize());
    }

    public StandardTransactionBuilder(GraphDatabaseConfiguration graphConfig, StandardTitanGraph graph, Configuration customOptions) {
        Preconditions.checkNotNull(graphConfig);
        Preconditions.checkNotNull(graph);
        if (graphConfig.isReadOnly()) readOnly();
        if (graphConfig.isBatchLoading()) enableBatchLoading();
        this.graph = graph;
        this.defaultSchemaMaker = graphConfig.getDefaultSchemaMaker();
        this.assignIDsImmediately = graphConfig.hasFlushIDs();
        this.forceIndexUsage = graphConfig.hasForceIndexUsage();
        this.groupName = graphConfig.getMetricsPrefix();
        this.logIdentifier = null;
        this.propertyPrefetching = graphConfig.hasPropertyPrefetching();
        this.writableCustomOptions = null;
        this.customOptions = customOptions;
        vertexCacheSize(graphConfig.getTxVertexCacheSize());
        dirtyVertexSize(graphConfig.getTxDirtyVertexSize());
    }

    public StandardTransactionBuilder threadBound() {
        this.threadBound = true;
        this.singleThreaded = true;
        return this;
    }

    @Override
    public StandardTransactionBuilder readOnly() {
        this.isReadOnly = true;
        return this;
    }

    @Override
    public StandardTransactionBuilder enableBatchLoading() {
        hasEnabledBatchLoading = true;
        checkExternalVertexExistence(false);
        consistencyChecks(false);
        return this;
    }

    @Override
    public StandardTransactionBuilder disableBatchLoading() {
        hasEnabledBatchLoading = false;
        checkExternalVertexExistence(true);
        consistencyChecks(true);
        return this;
    }

    @Override
    public StandardTransactionBuilder vertexCacheSize(int size) {
        Preconditions.checkArgument(size >= 0);
        this.vertexCacheSize = size;
        this.indexCacheWeight = size / 2;
        return this;
    }

    @Override
    public TransactionBuilder dirtyVertexSize(int size) {
        this.dirtyVertexSize = size;
        return this;
    }

    @Override
    public StandardTransactionBuilder checkInternalVertexExistence(boolean enabled) {
        this.verifyInternalVertexExistence = enabled;
        return this;
    }

    @Override
    public StandardTransactionBuilder checkExternalVertexExistence(boolean enabled) {
        this.verifyExternalVertexExistence = enabled;
        return this;
    }

    @Override
    public TransactionBuilder consistencyChecks(boolean enabled) {
        this.verifyUniqueness = enabled;
        this.acquireLocks = enabled;
        return this;
    }

    @Override
    public StandardTransactionBuilder commitTime(Instant timestampSinceEpoch) {
        this.userCommitTime = timestampSinceEpoch;
        return this;
    }

    @Override
    public void setCommitTime(Instant time) {
        throw new UnsupportedOperationException("Use setCommitTime(lnog,TimeUnit)");
    }

    @Override
    public StandardTransactionBuilder groupName(String p) {
        this.groupName = p;
        return this;
    }

    @Override
    public StandardTransactionBuilder logIdentifier(String logName) {

        this.logIdentifier = logName;
        return this;
    }

    @Override
    public TransactionBuilder restrictedPartitions(int[] partitions) {
        Preconditions.checkNotNull(partitions);
        this.restrictedPartitions=partitions;
        return this;
    }

    public TransactionBuilder setPreloadedData(boolean preloaded) {
        this.preloadedData = preloaded;
        return this;
    }


    @Override
    public TransactionBuilder customOption(String k, Object v) {
        if (null == writableCustomOptions)
            throw new IllegalStateException("This builder was not constructed with setCustomOption support");
        writableCustomOptions.set((ConfigOption<Object>)ConfigElement.parse(ROOT_NS, k).element, v);
        return this;
    }

    @Override
    public TitanTransaction start() {
        TransactionConfiguration immutable = new ImmutableTxCfg(isReadOnly, hasEnabledBatchLoading,
                assignIDsImmediately, preloadedData, forceIndexUsage, verifyExternalVertexExistence,
                verifyInternalVertexExistence, acquireLocks, verifyUniqueness,
                propertyPrefetching, singleThreaded, threadBound, getTimestampProvider(), userCommitTime,
                indexCacheWeight, getVertexCacheSize(), getDirtyVertexSize(),
                logIdentifier, restrictedPartitions, groupName,
                defaultSchemaMaker, customOptions);
        return graph.newTransaction(immutable);
    }

    /* ##############################################
                    TransactionConfig
    ############################################## */


    @Override
    public final boolean isReadOnly() {
        return isReadOnly;
    }

    @Override
    public final boolean hasAssignIDsImmediately() {
        return assignIDsImmediately;
    }

    @Override
    public boolean hasPreloadedData() { return preloadedData; }

    @Override
    public final boolean hasForceIndexUsage() {
        return forceIndexUsage;
    }

    @Override
    public boolean hasEnabledBatchLoading() {
        return hasEnabledBatchLoading;
    }

    @Override
    public final boolean hasVerifyExternalVertexExistence() {
        return verifyExternalVertexExistence;
    }

    @Override
    public final boolean hasVerifyInternalVertexExistence() {
        return verifyInternalVertexExistence;
    }

    @Override
    public final boolean hasAcquireLocks() {
        return acquireLocks;
    }

    @Override
    public final DefaultSchemaMaker getAutoSchemaMaker() {
        return defaultSchemaMaker;
    }

    @Override
    public final boolean hasVerifyUniqueness() {
        return verifyUniqueness;
    }

    public boolean hasPropertyPrefetching() {
        return propertyPrefetching;
    }

    @Override
    public final boolean isSingleThreaded() {
        return singleThreaded;
    }

    @Override
    public final boolean isThreadBound() {
        return threadBound;
    }

    @Override
    public final int getVertexCacheSize() {
        return vertexCacheSize;
    }

    @Override
    public final int getDirtyVertexSize() {
        return dirtyVertexSize;
    }

    @Override
    public final long getIndexCacheWeight() {
        return indexCacheWeight;
    }

    @Override
    public String getLogIdentifier() {
        return logIdentifier;
    }

    @Override
    public int[] getRestrictedPartitions() {
        return restrictedPartitions;
    }

    @Override
    public boolean hasRestrictedPartitions() {
        return restrictedPartitions.length>0;
    }

    @Override
    public String getGroupName() {
        return groupName;
    }

    @Override
    public boolean hasGroupName() {
        return null != groupName;
    }

    @Override
    public Instant getCommitTime() {
        return userCommitTime;
    }

    @Override
    public boolean hasCommitTime() {
        return userCommitTime!=null;
    }

    @Override
    public <V> V getCustomOption(ConfigOption<V> opt) {
        return getCustomOptions().get(opt);
    }

    @Override
    public Configuration getCustomOptions() {
        return customOptions;
    }

    @Override
    public TimestampProvider getTimestampProvider() {
        return graph.getConfiguration().getTimestampProvider();
    }

    private static class ImmutableTxCfg implements TransactionConfiguration {

        private final boolean isReadOnly;
        private final boolean hasEnabledBatchLoading;
        private final boolean hasAssignIDsImmediately;
        private final boolean hasPreloadedData;
        private final boolean hasForceIndexUsage;
        private final boolean hasVerifyExternalVertexExistence;
        private final boolean hasVerifyInternalVertexExistence;
        private final boolean hasAcquireLocks;
        private final boolean hasVerifyUniqueness;
        private final boolean hasPropertyPrefetching;
        private final boolean isSingleThreaded;
        private final boolean isThreadBound;
        private final long indexCacheWeight;
        private final int vertexCacheSize;
        private final int dirtyVertexSize;
        private final String logIdentifier;
        private final int[] restrictedPartitions;
        private final DefaultSchemaMaker defaultSchemaMaker;

        private final BaseTransactionConfig handleConfig;

        public ImmutableTxCfg(boolean isReadOnly,
                boolean hasEnabledBatchLoading,
                boolean hasAssignIDsImmediately,
                boolean hasPreloadedData,
                boolean hasForceIndexUsage,
                boolean hasVerifyExternalVertexExistence,
                boolean hasVerifyInternalVertexExistence,
                boolean hasAcquireLocks, boolean hasVerifyUniqueness,
                boolean hasPropertyPrefetching, boolean isSingleThreaded,
                boolean isThreadBound, TimestampProvider times, Instant commitTime,
                long indexCacheWeight, int vertexCacheSize, int dirtyVertexSize, String logIdentifier,
                int[] restrictedPartitions,
                String groupName, DefaultSchemaMaker defaultSchemaMaker,
                Configuration customOptions) {
            this.isReadOnly = isReadOnly;
            this.hasEnabledBatchLoading = hasEnabledBatchLoading;
            this.hasAssignIDsImmediately = hasAssignIDsImmediately;
            this.hasPreloadedData = hasPreloadedData;
            this.hasForceIndexUsage = hasForceIndexUsage;
            this.hasVerifyExternalVertexExistence = hasVerifyExternalVertexExistence;
            this.hasVerifyInternalVertexExistence = hasVerifyInternalVertexExistence;
            this.hasAcquireLocks = hasAcquireLocks;
            this.hasVerifyUniqueness = hasVerifyUniqueness;
            this.hasPropertyPrefetching = hasPropertyPrefetching;
            this.isSingleThreaded = isSingleThreaded;
            this.isThreadBound = isThreadBound;
            this.indexCacheWeight = indexCacheWeight;
            this.vertexCacheSize = vertexCacheSize;
            this.dirtyVertexSize = dirtyVertexSize;
            this.logIdentifier = logIdentifier;
            this.restrictedPartitions=restrictedPartitions;
            this.defaultSchemaMaker = defaultSchemaMaker;
            this.handleConfig = new StandardBaseTransactionConfig.Builder()
                    .commitTime(commitTime)
                    .timestampProvider(times)
                    .groupName(groupName)
                    .customOptions(customOptions).build();
        }

        @Override
        public boolean hasEnabledBatchLoading() {
            return hasEnabledBatchLoading;
        }

        @Override
        public boolean isReadOnly() {
            return isReadOnly;
        }

        @Override
        public boolean hasAssignIDsImmediately() {
            return hasAssignIDsImmediately;
        }

        @Override
        public boolean hasPreloadedData() {
            return hasPreloadedData;
        }

        @Override
        public final boolean hasForceIndexUsage() {
            return hasForceIndexUsage;
        }

        @Override
        public boolean hasVerifyExternalVertexExistence() {
            return hasVerifyExternalVertexExistence;
        }

        @Override
        public boolean hasVerifyInternalVertexExistence() {
            return hasVerifyInternalVertexExistence;
        }

        @Override
        public boolean hasAcquireLocks() {
            return hasAcquireLocks;
        }

        @Override
        public DefaultSchemaMaker getAutoSchemaMaker() {
            return defaultSchemaMaker;
        }

        @Override
        public boolean hasVerifyUniqueness() {
            return hasVerifyUniqueness;
        }

        @Override
        public boolean hasPropertyPrefetching() {
            return hasPropertyPrefetching;
        }

        @Override
        public boolean isSingleThreaded() {
            return isSingleThreaded;
        }

        @Override
        public boolean isThreadBound() {
            return isThreadBound;
        }

        @Override
        public int getVertexCacheSize() {
            return vertexCacheSize;
        }

        @Override
        public int getDirtyVertexSize() {
            return dirtyVertexSize;
        }

        @Override
        public long getIndexCacheWeight() {
            return indexCacheWeight;
        }

        @Override
        public String getLogIdentifier() {
            return logIdentifier;
        }

        @Override
        public int[] getRestrictedPartitions() {
            return restrictedPartitions;
        }

        @Override
        public boolean hasRestrictedPartitions() {
            return restrictedPartitions.length>0;
        }

        @Override
        public Instant getCommitTime() {
            return handleConfig.getCommitTime();
        }

        @Override
        public void setCommitTime(Instant time) {
            handleConfig.setCommitTime(time);
        }

        @Override
        public boolean hasCommitTime() {
            return handleConfig.hasCommitTime();
        }

        @Override
        public String getGroupName() {
            return handleConfig.getGroupName();
        }

        @Override
        public boolean hasGroupName() {
            return handleConfig.hasGroupName();
        }

        @Override
        public <V> V getCustomOption(ConfigOption<V> opt) {
            return handleConfig.getCustomOption(opt);
        }

        @Override
        public Configuration getCustomOptions() {
            return handleConfig.getCustomOptions();
        }

        @Override
        public TimestampProvider getTimestampProvider() {
            return handleConfig.getTimestampProvider();
        }
    }
}
