/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.loadbalance.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.BrokerFilter;
import org.apache.pulsar.broker.loadbalance.BrokerFilterException;
import org.apache.pulsar.broker.loadbalance.BrokerHostUsage;
import org.apache.pulsar.broker.loadbalance.BundleSplitStrategy;
import org.apache.pulsar.broker.loadbalance.LoadData;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.LoadSheddingStrategy;
import org.apache.pulsar.broker.loadbalance.ModularLoadManager;
import org.apache.pulsar.broker.loadbalance.ModularLoadManagerStrategy;
import org.apache.pulsar.broker.loadbalance.extensions.models.TopKBundles;
import org.apache.pulsar.broker.loadbalance.impl.LoadManagerShared.BrokerTopicLoadingPredicate;
import org.apache.pulsar.broker.resources.PulsarResources;
import org.apache.pulsar.broker.stats.prometheus.metrics.Summary;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.util.ExecutorProvider;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundleFactory;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.policies.data.ResourceQuota;
import org.apache.pulsar.common.stats.Metrics;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.Reflections;
import org.apache.pulsar.metadata.api.MetadataStoreException;
import org.apache.pulsar.metadata.api.MetadataStoreException.NotFoundException;
import org.apache.pulsar.metadata.api.Notification;
import org.apache.pulsar.metadata.api.coordination.LockManager;
import org.apache.pulsar.metadata.api.coordination.ResourceLock;
import org.apache.pulsar.metadata.api.extended.SessionEvent;
import org.apache.pulsar.policies.data.loadbalancer.BrokerData;
import org.apache.pulsar.policies.data.loadbalancer.BundleData;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.policies.data.loadbalancer.SystemResourceUsage;
import org.apache.pulsar.policies.data.loadbalancer.TimeAverageBrokerData;
import org.apache.pulsar.policies.data.loadbalancer.TimeAverageMessageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModularLoadManagerImpl implements ModularLoadManager {
    private static final Logger log = LoggerFactory.getLogger(ModularLoadManagerImpl.class);

    // Default message rate to assume for unseen bundles.
    public static final double DEFAULT_MESSAGE_RATE = 50;

    // Default message throughput to assume for unseen bundles.
    // Note that the default message size is implicitly defined as DEFAULT_MESSAGE_THROUGHPUT / DEFAULT_MESSAGE_RATE.
    public static final double DEFAULT_MESSAGE_THROUGHPUT = 50000;

    // The number of effective samples to keep for observing long term data.
    public static final int NUM_LONG_SAMPLES = 1000;

    // The number of effective samples to keep for observing short term data.
    public static final int NUM_SHORT_SAMPLES = 10;

    // Set of broker candidates to reuse so that object creation is avoided.
    private final Set<String> brokerCandidateCache;

    // Cache of the local broker data, stored in LoadManager.LOADBALANCE_BROKER_ROOT.
    private LockManager<LocalBrokerData> brokersData;
    private ResourceLock<LocalBrokerData> brokerDataLock;

    // Broker host usage object used to calculate system resource usage.
    private BrokerHostUsage brokerHostUsage;

    private final BundleRangeCache brokerToNamespaceToBundleRange = new BundleRangeCache();

    // Path to the ZNode containing the LocalBrokerData json for this broker.
    private String brokerZnodePath;

    // Strategy to use for splitting bundles.
    private BundleSplitStrategy bundleSplitStrategy;

    // Service configuration belonging to the pulsar service.
    private ServiceConfiguration conf;

    // The default bundle stats which are used to initialize historic data.
    // This data is overridden after the bundle receives its first sample.
    private final NamespaceBundleStats defaultStats;

    // Used to filter brokers from being selected for assignment.
    private final List<BrokerFilter> filterPipeline;

    // LocalBrokerData available before most recent update.
    private LocalBrokerData lastData;

    // Used to determine what namespaces, if any, should be unloaded.
    private LoadSheddingStrategy loadSheddingStrategy;

    // Local data for the broker this is running on.
    private LocalBrokerData localData;

    // Load data comprising data available for each broker.
    private final LoadData loadData;

    // Used to determine whether a bundle is preallocated.
    private final Map<String, String> preallocatedBundleToBroker;

    // Strategy used to determine where new topics should be placed.
    private ModularLoadManagerStrategy placementStrategy;

    private ModularLoadManagerStrategy sheddingExcludedNamespaceSelectionStrategy;

    // Policies used to determine which brokers are available for particular namespaces.
    private SimpleResourceAllocationPolicies policies;

    @VisibleForTesting
    // Pulsar service used to initialize this.
    protected PulsarService pulsar;

    private PulsarResources pulsarResources;

    // Executor service used to update broker data.
    private final ExecutorService executors;

    // check if given broker can load persistent/non-persistent topic
    private final BrokerTopicLoadingPredicate brokerTopicLoadingPredicate;

    private Map<String, String> brokerToFailureDomainMap;

    private SessionEvent lastMetadataSessionEvent = SessionEvent.Reconnected;

    // record load balancing metrics
    private AtomicReference<List<Metrics>> loadBalancingMetrics = new AtomicReference<>();
    // record bundle unload metrics
    private AtomicReference<List<Metrics>> bundleUnloadMetrics = new AtomicReference<>();
    // record bundle split metrics
    private AtomicReference<List<Metrics>> bundleSplitMetrics = new AtomicReference<>();
    // record bundle metrics
    private AtomicReference<List<Metrics>> bundleMetrics = new AtomicReference<>();

    private long bundleSplitCount = 0;
    private long unloadBrokerCount = 0;
    private long unloadBundleCount = 0;

    private final Lock lock = new ReentrantLock();
    private final Set<String> knownBrokers = new HashSet<>();
    private Map<String, String> bundleBrokerAffinityMap;
    // array used for sorting and select topK bundles
    private final List<Map.Entry<String, ? extends Comparable>> bundleArr = new ArrayList<>();


    /**
     * Initializes fields which do not depend on PulsarService. initialize(PulsarService) should subsequently be called.
     */
    public ModularLoadManagerImpl() {
        brokerCandidateCache = new HashSet<>();
        defaultStats = new NamespaceBundleStats();
        filterPipeline = new ArrayList<>();
        loadData = new LoadData();
        preallocatedBundleToBroker = new ConcurrentHashMap<>();
        executors = Executors.newSingleThreadExecutor(
                new ExecutorProvider.ExtendedThreadFactory("pulsar-modular-load-manager"));
        this.brokerToFailureDomainMap = new HashMap<>();
        this.bundleBrokerAffinityMap = new ConcurrentHashMap<>();
        this.brokerTopicLoadingPredicate = new BrokerTopicLoadingPredicate() {
            @Override
            public boolean isEnablePersistentTopics(String brokerId) {
                final BrokerData brokerData = loadData.getBrokerData().get(brokerId);
                return brokerData != null && brokerData.getLocalData() != null
                        && brokerData.getLocalData().isPersistentTopicsEnabled();
            }

            @Override
            public boolean isEnableNonPersistentTopics(String brokerId) {
                final BrokerData brokerData = loadData.getBrokerData().get(brokerId);
                return brokerData != null && brokerData.getLocalData() != null
                        && brokerData.getLocalData().isNonPersistentTopicsEnabled();
            }
        };
    }

    /**
     * Initialize this load manager using the given PulsarService. Should be called only once, after invoking the
     * default constructor.
     *
     * @param pulsar The service to initialize with.
     */
    @Override
    public void initialize(final PulsarService pulsar) {
        this.pulsar = pulsar;
        this.pulsarResources = pulsar.getPulsarResources();
        brokersData = pulsar.getCoordinationService().getLockManager(LocalBrokerData.class);
        pulsar.getLocalMetadataStore().registerListener(this::handleDataNotification);
        pulsar.getLocalMetadataStore().registerSessionListener(this::handleMetadataSessionEvent);

        if (SystemUtils.IS_OS_LINUX) {
            brokerHostUsage = new LinuxBrokerHostUsageImpl(pulsar);
        } else {
            brokerHostUsage = new GenericBrokerHostUsageImpl(pulsar);
        }

        bundleSplitStrategy = new BundleSplitterTask();

        conf = pulsar.getConfiguration();

        // Initialize the default stats to assume for unseen bundles (hard-coded for now).
        defaultStats.msgThroughputIn = DEFAULT_MESSAGE_THROUGHPUT;
        defaultStats.msgThroughputOut = DEFAULT_MESSAGE_THROUGHPUT;
        defaultStats.msgRateIn = DEFAULT_MESSAGE_RATE;
        defaultStats.msgRateOut = DEFAULT_MESSAGE_RATE;

        placementStrategy = ModularLoadManagerStrategy.create(conf);
        sheddingExcludedNamespaceSelectionStrategy = new RoundRobinBrokerSelector();
        policies = new SimpleResourceAllocationPolicies(pulsar);
        filterPipeline.add(new BrokerLoadManagerClassFilter());
        filterPipeline.add(new BrokerVersionFilter());

        LoadManagerShared.refreshBrokerToFailureDomainMap(pulsar, brokerToFailureDomainMap);
        // register listeners for domain changes
        pulsarResources.getClusterResources().getFailureDomainResources()
                .registerListener(__ -> {
                    executors.execute(
                            () -> LoadManagerShared.refreshBrokerToFailureDomainMap(pulsar, brokerToFailureDomainMap));
                });

        if (placementStrategy instanceof LoadSheddingStrategy) {
            // if the placement strategy is also a load shedding strategy
            // we need to check two strategies are the same
            if (!conf.getLoadBalancerLoadSheddingStrategy().equals(
                    conf.getLoadBalancerLoadPlacementStrategy())) {
                throw new IllegalArgumentException("The load shedding strategy: "
                        + conf.getLoadBalancerLoadSheddingStrategy()
                        + " can't work with the placement strategy: "
                        + conf.getLoadBalancerLoadPlacementStrategy());
            }
            // bind the load shedding strategy and the placement strategy
            loadSheddingStrategy = (LoadSheddingStrategy) placementStrategy;
        } else {
            loadSheddingStrategy = createLoadSheddingStrategy();
        }
    }

    public void handleDataNotification(Notification t) {
        if (t.getPath().startsWith(LoadManager.LOADBALANCE_BROKERS_ROOT)) {
            brokersData.listLocks(LoadManager.LOADBALANCE_BROKERS_ROOT)
                    .thenAccept(brokers -> {
                        reapDeadBrokerPreallocations(brokers);
                    });

            try {
                executors.execute(ModularLoadManagerImpl.this::updateAll);
            } catch (RejectedExecutionException e) {
                // Executor is shutting down
            }
        }
    }

    private void handleMetadataSessionEvent(SessionEvent e) {
        lastMetadataSessionEvent = e;
    }

    private LoadSheddingStrategy createLoadSheddingStrategy() {
        return Reflections.createInstance(conf.getLoadBalancerLoadSheddingStrategy(), LoadSheddingStrategy.class,
                Thread.currentThread().getContextClassLoader());
    }

    /**
     * Initialize this load manager.
     *
     * @param pulsar
     *            Client to construct this manager from.
     */
    public ModularLoadManagerImpl(final PulsarService pulsar) {
        this();
        initialize(pulsar);
    }

    // For each broker that we have a recent load report, see if they are still alive
    private void reapDeadBrokerPreallocations(List<String> aliveBrokers) {
        for (String broker : loadData.getBrokerData().keySet()) {
            if (!aliveBrokers.contains(broker)) {
                if (log.isDebugEnabled()) {
                    log.debug("Broker {} appears to have stopped; now reclaiming any preallocations", broker);
                }
                final Iterator<Map.Entry<String, String>> iterator = preallocatedBundleToBroker.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, String> entry = iterator.next();
                    final String preallocatedBundle = entry.getKey();
                    final String preallocatedBroker = entry.getValue();
                    if (broker.equals(preallocatedBroker)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Removing old preallocation on dead broker {} for bundle {}",
                                    preallocatedBroker, preallocatedBundle);
                        }
                        iterator.remove();
                    }
                }
            }
        }
    }

    @Override
    public Set<String> getAvailableBrokers() {
        try {
            return getAvailableBrokersAsync().get(conf.getMetadataStoreOperationTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error when trying to get active brokers", e);
            return loadData.getBrokerData().keySet();
        }
    }

    @Override
    public CompletableFuture<Set<String>> getAvailableBrokersAsync() {
        CompletableFuture<Set<String>> future = new CompletableFuture<>();
        brokersData.listLocks(LoadManager.LOADBALANCE_BROKERS_ROOT)
                .whenComplete((listLocks, ex) -> {
                    if (ex != null){
                        Throwable realCause = FutureUtil.unwrapCompletionException(ex);
                        log.warn("Error when trying to get active brokers", realCause);
                        future.complete(loadData.getBrokerData().keySet());
                    } else {
                        future.complete(Sets.newHashSet(listLocks));
                    }
                });
        return future;
    }

    // Attempt to local the data for the given bundle in metadata store
    // If it cannot be found, return the default bundle data.
    @Override
    public BundleData getBundleDataOrDefault(final String bundle) {
        BundleData bundleData = null;
        try {
            Optional<BundleData> optBundleData =
                    pulsarResources.getLoadBalanceResources().getBundleDataResources().getBundleData(bundle).join();
            if (optBundleData.isPresent()) {
                return optBundleData.get();
            }

            Optional<ResourceQuota> optQuota = pulsarResources.getLoadBalanceResources().getQuotaResources()
                    .getQuota(bundle).join();
            if (optQuota.isPresent()) {
                ResourceQuota quota = optQuota.get();
                bundleData = new BundleData(NUM_SHORT_SAMPLES, NUM_LONG_SAMPLES);
                // Initialize from existing resource quotas if new API ZNodes do not exist.
                final TimeAverageMessageData shortTermData = bundleData.getShortTermData();
                final TimeAverageMessageData longTermData = bundleData.getLongTermData();

                shortTermData.setMsgRateIn(quota.getMsgRateIn());
                shortTermData.setMsgRateOut(quota.getMsgRateOut());
                shortTermData.setMsgThroughputIn(quota.getBandwidthIn());
                shortTermData.setMsgThroughputOut(quota.getBandwidthOut());

                longTermData.setMsgRateIn(quota.getMsgRateIn());
                longTermData.setMsgRateOut(quota.getMsgRateOut());
                longTermData.setMsgThroughputIn(quota.getBandwidthIn());
                longTermData.setMsgThroughputOut(quota.getBandwidthOut());

                // Assume ample history.
                shortTermData.setNumSamples(NUM_SHORT_SAMPLES);
                longTermData.setNumSamples(NUM_LONG_SAMPLES);
            }
        } catch (Exception e) {
            log.warn("Error when trying to find bundle {} on metadata store: {}", bundle, e);
        }
        if (bundleData == null) {
            bundleData = new BundleData(NUM_SHORT_SAMPLES, NUM_LONG_SAMPLES, defaultStats);
        }
        return bundleData;
    }

    // Use the Pulsar client to acquire the namespace bundle stats.
    private Map<String, NamespaceBundleStats> getBundleStats() {
        return pulsar.getBrokerService().getBundleStats();
    }

    private double percentChange(final double oldValue, final double newValue) {
        if (oldValue == 0) {
            if (newValue == 0) {
                // Avoid NaN
                return 0;
            }
            return Double.POSITIVE_INFINITY;
        }
        return 100 * Math.abs((oldValue - newValue) / oldValue);
    }

    private double getMaxResourceUsageWithWeight(LocalBrokerData localBrokerData, ServiceConfiguration conf) {
        return localBrokerData.getMaxResourceUsageWithWeight(
                conf.getLoadBalancerCPUResourceWeight(),
                conf.getLoadBalancerDirectMemoryResourceWeight(),
                conf.getLoadBalancerBandwidthInResourceWeight(),
                conf.getLoadBalancerBandwidthOutResourceWeight());
    }

    // Determine if the broker data requires an update by delegating to the update condition.
    private boolean needBrokerDataUpdate() {
        final long updateMaxIntervalMillis = TimeUnit.MINUTES
                .toMillis(conf.getLoadBalancerReportUpdateMaxIntervalMinutes());
        long timeSinceLastReportWrittenToStore = System.currentTimeMillis() - localData.getLastUpdate();
        if (timeSinceLastReportWrittenToStore > updateMaxIntervalMillis) {
            log.info("Writing local data to metadata store because time since last"
                            + " update exceeded threshold of {} minutes",
                    conf.getLoadBalancerReportUpdateMaxIntervalMinutes());
            // Always update after surpassing the maximum interval.
            return true;
        }
        final double maxChange = LocalBrokerData.max(
                percentChange(lastData.getMsgRateIn() + lastData.getMsgRateOut(),
                        localData.getMsgRateIn() + localData.getMsgRateOut()),
                percentChange(lastData.getMsgThroughputIn() + lastData.getMsgThroughputOut(),
                        localData.getMsgThroughputIn() + localData.getMsgThroughputOut()),
                percentChange(lastData.getNumBundles(), localData.getNumBundles()),
                100.0 * Math.abs(getMaxResourceUsageWithWeight(lastData, conf)
                        - getMaxResourceUsageWithWeight(localData, conf))
        );
        if (maxChange > conf.getLoadBalancerReportUpdateThresholdPercentage()) {
            log.info("Writing local data to metadata store because maximum change {}% exceeded threshold {}%; "
                            + "time since last report written is {} seconds", maxChange,
                    conf.getLoadBalancerReportUpdateThresholdPercentage(),
                    timeSinceLastReportWrittenToStore / 1000.0);
            return true;
        }
        return false;
    }

    // Update both the broker data and the bundle data.
    public void updateAll() {
        if (log.isDebugEnabled()) {
            log.debug("Updating broker and bundle data for loadreport");
        }
        cleanupDeadBrokersData();
        updateAllBrokerData();
        updateBundleData();
        // broker has latest load-report: check if any bundle requires split
        checkNamespaceBundleSplit();
    }

    private synchronized void cleanupDeadBrokersData() {
        final Set<String> activeBrokers = getAvailableBrokers();
        Collection<String> deadBrokers = CollectionUtils.subtract(knownBrokers, activeBrokers);
        this.knownBrokers.clear();
        this.knownBrokers.addAll(activeBrokers);
        if (pulsar.getLeaderElectionService() != null
                && pulsar.getLeaderElectionService().isLeader()) {
            deadBrokers.forEach(this::deleteTimeAverageDataFromMetadataStoreAsync);
            loadSheddingStrategy.onActiveBrokersChange(activeBrokers);
            placementStrategy.onActiveBrokersChange(activeBrokers);
        }
    }

    // As the leader broker, update the broker data map in loadData by querying metadata store for the broker data put
    // there by each broker via updateLocalBrokerData.
    private void updateAllBrokerData() {
        final Set<String> activeBrokers = getAvailableBrokers();
        final Map<String, BrokerData> brokerDataMap = loadData.getBrokerData();
        for (String broker : activeBrokers) {
            try {
                String key = String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, broker);
                Optional<LocalBrokerData> localData = brokersData.readLock(key).get();
                if (!localData.isPresent()) {
                    brokerDataMap.remove(broker);
                    log.info("[{}] Broker load report is not present", broker);
                    continue;
                }

                if (brokerDataMap.containsKey(broker)) {
                    // Replace previous local broker data.
                    brokerDataMap.get(broker).setLocalData(localData.get());
                } else {
                    // Initialize BrokerData object for previously unseen
                    // brokers.
                    brokerDataMap.put(broker, new BrokerData(localData.get()));
                }
            } catch (Exception e) {
                log.warn("Error reading broker data from cache for broker - [{}], [{}]", broker, e.getMessage());
            }
        }
        // Remove obsolete brokers.
        for (final String broker : brokerDataMap.keySet()) {
            if (!activeBrokers.contains(broker)) {
                brokerDataMap.remove(broker);
            }
        }
    }

    // As the leader broker, use the local broker data saved on metadata store to update the bundle stats so that better
    // load management decisions may be made.
    private void updateBundleData() {
        final Map<String, BundleData> bundleData = loadData.getBundleData();
        final Set<String> activeBundles = new HashSet<>();
        // Iterate over the broker data.
        for (Map.Entry<String, BrokerData> brokerEntry : loadData.getBrokerData().entrySet()) {
            final String broker = brokerEntry.getKey();
            final BrokerData brokerData = brokerEntry.getValue();
            final Map<String, NamespaceBundleStats> statsMap = brokerData.getLocalData().getLastStats();

            // Iterate over the last bundle stats available to the current
            // broker to update the bundle data.
            for (Map.Entry<String, NamespaceBundleStats> entry : statsMap.entrySet()) {
                final String bundle = entry.getKey();
                final NamespaceBundleStats stats = entry.getValue();
                activeBundles.add(bundle);
                if (bundleData.containsKey(bundle)) {
                    // If we recognize the bundle, add these stats as a new sample.
                    bundleData.get(bundle).update(stats);
                } else {
                    // Otherwise, attempt to find the bundle data on metadata store.
                    // If it cannot be found, use the latest stats as the first sample.
                    BundleData currentBundleData = getBundleDataOrDefault(bundle);
                    currentBundleData.update(stats);
                    bundleData.put(bundle, currentBundleData);
                }
            }
            // Remove all loaded bundles from the preallocated maps.
            final Map<String, BundleData> preallocatedBundleData = brokerData.getPreallocatedBundleData();
            Set<String> ownedNsBundles = pulsar.getNamespaceService().getOwnedServiceUnits()
                    .stream().map(NamespaceBundle::toString).collect(Collectors.toSet());
            synchronized (preallocatedBundleData) {
                preallocatedBundleToBroker.keySet().removeAll(preallocatedBundleData.keySet());
                final Iterator<Map.Entry<String, BundleData>> preallocatedIterator =
                        preallocatedBundleData.entrySet().iterator();
                while (preallocatedIterator.hasNext()) {
                    final String bundle = preallocatedIterator.next().getKey();
                    if (!ownedNsBundles.contains(bundle)
                            || (brokerData.getLocalData().getBundles().contains(bundle)
                            && bundleData.containsKey(bundle))) {
                        preallocatedIterator.remove();
                    }
                }
            }

            // Using the newest data, update the aggregated time-average data for the current broker.
            TimeAverageBrokerData timeAverageData = new TimeAverageBrokerData();
            timeAverageData.reset(statsMap.keySet(), bundleData, defaultStats);
            brokerData.setTimeAverageData(timeAverageData);

            brokerToNamespaceToBundleRange.reloadFromBundles(broker,
                    Stream.of(statsMap.keySet(), preallocatedBundleData.keySet()).flatMap(Collection::stream));
        }

        // Remove not active bundle from loadData
        for (String bundle : bundleData.keySet()) {
            if (!activeBundles.contains(bundle)){
                bundleData.remove(bundle);
                if (pulsar.getLeaderElectionService() != null && pulsar.getLeaderElectionService().isLeader()){
                    deleteBundleDataFromMetadataStore(bundle);
                }
            }
        }
    }

    /**
     * As any broker, disable the broker this manager is running on.
     *
     * @throws PulsarServerException
     *             If there's a failure when disabling broker on metadata store.
     */
    @Override
    public void disableBroker() throws PulsarServerException {
        if (StringUtils.isNotEmpty(brokerZnodePath)) {
            try {
                brokerDataLock.release().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof NotFoundException) {
                    throw new PulsarServerException.NotFoundException(MetadataStoreException.unwrap(e));
                } else {
                    throw new PulsarServerException(MetadataStoreException.unwrap(e));
                }
            }
        }
    }

    /**
     * As the leader broker, select bundles for the namespace service to unload so that they may be reassigned to new
     * brokers.
     */
    @Override
    public synchronized void doLoadShedding() {
        if (!LoadManagerShared.isLoadSheddingEnabled(pulsar)) {
            return;
        }
        if (getAvailableBrokers().size() <= 1) {
            log.info("Only 1 broker available: no load shedding will be performed");
            return;
        }
        // Remove bundles who have been unloaded for longer than the grace period from the recently unloaded map.
        final long timeout = System.currentTimeMillis()
                - TimeUnit.MINUTES.toMillis(conf.getLoadBalancerSheddingGracePeriodMinutes());
        final Map<String, Long> recentlyUnloadedBundles = loadData.getRecentlyUnloadedBundles();
        recentlyUnloadedBundles.keySet().removeIf(e -> recentlyUnloadedBundles.get(e) < timeout);

        Set<String> sheddingExcludedNamespaces = conf.getLoadBalancerSheddingExcludedNamespaces();
        final Multimap<String, String> bundlesToUnload = loadSheddingStrategy.findBundlesForUnloading(loadData, conf);

        bundlesToUnload.asMap().forEach((broker, bundles) -> {
            AtomicBoolean unloadBundleForBroker = new AtomicBoolean(false);
            bundles.forEach(bundle -> {
                final String namespaceName = LoadManagerShared.getNamespaceNameFromBundleName(bundle);
                final String bundleRange = LoadManagerShared.getBundleRangeFromBundleName(bundle);
                if (sheddingExcludedNamespaces.contains(namespaceName)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Skipping load shedding for namespace {}",
                                loadSheddingStrategy.getClass().getSimpleName(), namespaceName);
                    }
                    return;
                }
                if (!shouldNamespacePoliciesUnload(namespaceName, bundleRange, broker)) {
                    return;
                }

                if (!shouldAntiAffinityNamespaceUnload(namespaceName, bundleRange, broker)) {
                    return;
                }
                NamespaceBundle bundleToUnload = LoadManagerShared.getNamespaceBundle(pulsar, bundle);
                Optional<String> destBroker = this.selectBroker(bundleToUnload);
                if (!destBroker.isPresent()) {
                    log.info("[{}] No broker available to unload bundle {} from broker {}",
                            loadSheddingStrategy.getClass().getSimpleName(), bundle, broker);
                    return;
                }
                if (destBroker.get().equals(broker)) {
                    log.warn("[{}] The destination broker {} is the same as the current owner broker for Bundle {}",
                            loadSheddingStrategy.getClass().getSimpleName(), destBroker.get(), bundle);
                    return;
                }

                log.info("[{}] Unloading bundle: {} from broker {} to dest broker {}",
                        loadSheddingStrategy.getClass().getSimpleName(), bundle, broker, destBroker.get());
                try {
                    pulsar.getAdminClient().namespaces()
                            .unloadNamespaceBundle(namespaceName, bundleRange, destBroker.get());
                    loadData.getRecentlyUnloadedBundles().put(bundle, System.currentTimeMillis());
                    unloadBundleCount++;
                    unloadBundleForBroker.set(true);
                } catch (PulsarServerException | PulsarAdminException e) {
                    log.warn("Error when trying to perform load shedding on {} for broker {}", bundle, broker, e);
                }
            });
            if (unloadBundleForBroker.get()) {
                unloadBrokerCount++;
            }
        });

        updateBundleUnloadingMetrics();
    }

    /**
     * As leader broker, update bundle unloading metrics.
     */
    private void updateBundleUnloadingMetrics() {
        List<Metrics> metrics = new ArrayList<>();
        Map<String, String> dimensions = new HashMap<>();

        dimensions.put("metric", "bundleUnloading");

        Metrics m = Metrics.create(dimensions);
        m.put("brk_lb_unload_broker_total", unloadBrokerCount);
        m.put("brk_lb_unload_bundle_total", unloadBundleCount);
        metrics.add(m);
        this.bundleUnloadMetrics.set(metrics);
    }

    public boolean shouldNamespacePoliciesUnload(String namespace, String bundle, String currentBroker) {
        synchronized (brokerCandidateCache) {
            brokerCandidateCache.clear();
            ServiceUnitId serviceUnit = pulsar.getNamespaceService().getNamespaceBundleFactory()
                    .getBundle(namespace, bundle);
            LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                    getAvailableBrokers(), brokerTopicLoadingPredicate);

            // if only current broker satisfy the NamespacePolicies should not unload.
            brokerCandidateCache.remove(currentBroker);
            return !brokerCandidateCache.isEmpty();
        }
    }

    public boolean shouldAntiAffinityNamespaceUnload(String namespace, String bundle, String currentBroker) {
        try {
            var antiAffinityGroupOptional = LoadManagerShared.getNamespaceAntiAffinityGroup(pulsar, namespace);
            if (antiAffinityGroupOptional.isEmpty()) {
                return true;
            }

            synchronized (brokerCandidateCache) {
                brokerCandidateCache.clear();
                ServiceUnitId serviceUnit = pulsar.getNamespaceService().getNamespaceBundleFactory()
                        .getBundle(namespace, bundle);
                LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                        getAvailableBrokers(), brokerTopicLoadingPredicate);
                return LoadManagerShared.shouldAntiAffinityNamespaceUnload(namespace, currentBroker, pulsar,
                        brokerToNamespaceToBundleRange, brokerCandidateCache);
            }

        } catch (Exception e) {
            log.warn("Failed to check anti-affinity namespace ownership for {}/{}/{}, {}", namespace, bundle,
                    currentBroker, e.getMessage());

        }
        return true;
    }

    /**
     * As the leader broker, attempt to automatically detect and split hot namespace bundles.
     */
    @Override
    public void checkNamespaceBundleSplit() {

        if (!conf.isLoadBalancerAutoBundleSplitEnabled() || pulsar.getLeaderElectionService() == null
                || !pulsar.getLeaderElectionService().isLeader() || knownBrokers.size() <= 1) {
            return;
        }
        final boolean unloadSplitBundles = pulsar.getConfiguration().isLoadBalancerAutoUnloadSplitBundlesEnabled();
        synchronized (bundleSplitStrategy) {
            final Map<String, String> bundlesToBeSplit = bundleSplitStrategy.findBundlesToSplit(loadData, pulsar);
            NamespaceBundleFactory namespaceBundleFactory = pulsar.getNamespaceService().getNamespaceBundleFactory();
            int splitCount = 0;
            for (String bundleName : bundlesToBeSplit.keySet()) {
                try {
                    final String namespaceName = LoadManagerShared.getNamespaceNameFromBundleName(bundleName);
                    final String bundleRange = LoadManagerShared.getBundleRangeFromBundleName(bundleName);
                    if (!namespaceBundleFactory
                            .canSplitBundle(namespaceBundleFactory.getBundle(namespaceName, bundleRange))) {
                        continue;
                    }

                    // Make sure the same bundle is not selected again.
                    loadData.getBundleData().remove(bundleName);
                    localData.getLastStats().remove(bundleName);
                    // Clear namespace bundle-cache
                    this.pulsar.getNamespaceService().getNamespaceBundleFactory()
                            .invalidateBundleCache(NamespaceName.get(namespaceName));
                    deleteBundleDataFromMetadataStore(bundleName);

                    // Check NamespacePolicies and AntiAffinityNamespace support unload bundle.
                    boolean isUnload = false;
                    String broker = bundlesToBeSplit.get(bundleName);
                    if (unloadSplitBundles
                            && shouldNamespacePoliciesUnload(namespaceName, bundleRange, broker)
                            && shouldAntiAffinityNamespaceUnload(namespaceName, bundleRange, broker)) {
                        isUnload = true;
                    }
                    log.info("Load-manager splitting bundle {} and unloading {}", bundleName, isUnload);
                    pulsar.getAdminClient().namespaces().splitNamespaceBundle(namespaceName, bundleRange,
                            isUnload, null);

                    splitCount++;
                    log.info("Successfully split namespace bundle {}", bundleName);
                } catch (Exception e) {
                    log.error("Failed to split namespace bundle {}", bundleName, e);
                }
            }

            updateBundleSplitMetrics(splitCount);
        }

    }

    /**
     * As leader broker, update bundle split metrics.
     *
     * @param bundlesSplit the number of bundles splits
     */
    private void updateBundleSplitMetrics(int bundlesSplit) {
        bundleSplitCount += bundlesSplit;

        List<Metrics> metrics = new ArrayList<>();
        Map<String, String> dimensions = new HashMap<>();

        dimensions.put("metric", "bundlesSplit");

        Metrics m = Metrics.create(dimensions);
        m.put("brk_lb_bundles_split_total", bundleSplitCount);
        metrics.add(m);
        this.bundleSplitMetrics.set(metrics);
    }

    private static final Summary selectBrokerForAssignment = Summary.build(
            "pulsar_broker_load_manager_bundle_assigment", "-")
            .quantile(0.50)
            .quantile(0.99)
            .quantile(0.999)
            .quantile(1.0)
            .register();

    /**
     * As the leader broker, find a suitable broker for the assignment of the given bundle.
     *
     * @param serviceUnit
     *            ServiceUnitId for the bundle.
     * @return The name of the selected broker, as it appears on metadata store.
     */
    @Override
    public Optional<String> selectBrokerForAssignment(final ServiceUnitId serviceUnit) {
        // Use brokerCandidateCache as a lock to reduce synchronization.
        long startTime = System.nanoTime();

        try {
            synchronized (brokerCandidateCache) {
                final String bundle = serviceUnit.toString();
                if (preallocatedBundleToBroker.containsKey(bundle)) {
                    // If the given bundle is already in preallocated, return the selected broker.
                    return Optional.of(preallocatedBundleToBroker.get(bundle));
                }

                Optional<String> broker = selectBroker(serviceUnit);
                if (!broker.isPresent()) {
                    // If no broker is selected, return empty.
                    return broker;
                }
                // Add new bundle to preallocated.
                preallocateBundle(bundle, broker.get());
                return broker;
            }
        } finally {
            selectBrokerForAssignment.observe(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        }
    }

    private void preallocateBundle(String bundle, String broker) {
        final BundleData data = loadData.getBundleData().computeIfAbsent(bundle,
                key -> getBundleDataOrDefault(bundle));
        loadData.getBrokerData().get(broker).getPreallocatedBundleData().put(bundle, data);
        preallocatedBundleToBroker.put(bundle, broker);

        final String namespaceName = LoadManagerShared.getNamespaceNameFromBundleName(bundle);
        final String bundleRange = LoadManagerShared.getBundleRangeFromBundleName(bundle);
        brokerToNamespaceToBundleRange.add(broker, namespaceName, bundleRange);
    }

    @VisibleForTesting
    Optional<String> selectBroker(final ServiceUnitId serviceUnit) {
        synchronized (brokerCandidateCache) {
            final String bundle = serviceUnit.toString();
            final BundleData data = loadData.getBundleData().computeIfAbsent(bundle,
                    key -> getBundleDataOrDefault(bundle));
            brokerCandidateCache.clear();
            LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                    getAvailableBrokers(),
                    brokerTopicLoadingPredicate);

            // filter brokers which owns topic higher than threshold
            LoadManagerShared.filterBrokersWithLargeTopicCount(brokerCandidateCache, loadData,
                    conf.getLoadBalancerBrokerMaxTopics());

            // distribute namespaces to domain and brokers according to anti-affinity-group
            LoadManagerShared.filterAntiAffinityGroupOwnedBrokers(pulsar, bundle,
                    brokerCandidateCache,
                    brokerToNamespaceToBundleRange, brokerToFailureDomainMap);

            // distribute bundles evenly to candidate-brokers if enable
            // or system-namespace bundles
            if (conf.isLoadBalancerDistributeBundlesEvenlyEnabled()
                    || serviceUnit.getNamespaceObject().equals(NamespaceName.SYSTEM_NAMESPACE)) {
                LoadManagerShared.removeMostServicingBrokersForNamespace(bundle,
                        brokerCandidateCache,
                        brokerToNamespaceToBundleRange);
                if (log.isDebugEnabled()) {
                    log.debug("enable distribute bundles evenly to candidate-brokers, broker candidate count={}",
                            brokerCandidateCache.size());
                }
            }

            log.info("{} brokers being considered for assignment of {}", brokerCandidateCache.size(), bundle);

            // Use the filter pipeline to finalize broker candidates.
            try {
                for (BrokerFilter filter : filterPipeline) {
                    filter.filter(brokerCandidateCache, data, loadData, conf);
                }
            } catch (BrokerFilterException x) {
                // restore the list of brokers to the full set
                LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                        getAvailableBrokers(),
                        brokerTopicLoadingPredicate);
            }

            if (brokerCandidateCache.isEmpty()) {
                // restore the list of brokers to the full set
                LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                        getAvailableBrokers(),
                        brokerTopicLoadingPredicate);
            }

            Optional<String> broker;
            // For shedding excluded namespaces, use RoundRobinBrokerSelector to assign the ownership,
            // it can make the assignment more average because these will not automatically rebalance to
            // another broker unless manually unloaded it.
            Set<String> sheddingExcludedNamespaces = conf.getLoadBalancerSheddingExcludedNamespaces();
            String namespaceNameFromBundleName = LoadManagerShared.getNamespaceNameFromBundleName(bundle);
            if (sheddingExcludedNamespaces.contains(namespaceNameFromBundleName)) {
                if (log.isDebugEnabled()) {
                    log.debug("Use round robin broker selector for {}", bundle);
                }
                broker = sheddingExcludedNamespaceSelectionStrategy
                        .selectBroker(brokerCandidateCache, data, loadData, conf);
            } else {
                // Choose a broker among the potentially smaller filtered list, when possible
                broker = placementStrategy.selectBroker(brokerCandidateCache, data, loadData, conf);
            }
            if (log.isDebugEnabled()) {
                log.debug("Selected broker {} from candidate brokers {}", broker, brokerCandidateCache);
            }

            if (!broker.isPresent()) {
                // No brokers available
                return broker;
            }

            final double overloadThreshold = conf.getLoadBalancerBrokerOverloadedThresholdPercentage() / 100.0;
            final double maxUsage = getMaxResourceUsageWithWeight(
                    loadData.getBrokerData().get(broker.get()).getLocalData(), conf);
            if (maxUsage > overloadThreshold) {
                // All brokers that were in the filtered list were overloaded, so check if there is a better broker
                LoadManagerShared.applyNamespacePolicies(serviceUnit, policies, brokerCandidateCache,
                        getAvailableBrokers(),
                        brokerTopicLoadingPredicate);
                Optional<String> brokerTmp =
                        placementStrategy.selectBroker(brokerCandidateCache, data, loadData, conf);
                if (brokerTmp.isPresent()) {
                    broker = brokerTmp;
                }
            }
            return broker;
        }
    }

    /**
     * As any broker, start the load manager.
     *
     * @throws PulsarServerException
     *             If an unexpected error prevented the load manager from being started.
     */
    @Override
    public void start() throws PulsarServerException {
        try {
            // At this point, the ports will be updated with the real port number that the server was assigned
            Map<String, String> protocolData = pulsar.getProtocolDataToAdvertise();

            lastData = new LocalBrokerData(pulsar.getWebServiceAddress(), pulsar.getWebServiceAddressTls(),
                    pulsar.getBrokerServiceUrl(), pulsar.getBrokerServiceUrlTls(), pulsar.getAdvertisedListeners());
            lastData.setProtocols(protocolData);
            // configure broker-topic mode
            lastData.setPersistentTopicsEnabled(pulsar.getConfiguration().isEnablePersistentTopics());
            lastData.setNonPersistentTopicsEnabled(pulsar.getConfiguration().isEnableNonPersistentTopics());

            localData = new LocalBrokerData(pulsar.getWebServiceAddress(), pulsar.getWebServiceAddressTls(),
                    pulsar.getBrokerServiceUrl(), pulsar.getBrokerServiceUrlTls(), pulsar.getAdvertisedListeners());
            localData.setProtocols(protocolData);
            localData.setBrokerVersionString(pulsar.getBrokerVersion());
            // configure broker-topic mode
            localData.setPersistentTopicsEnabled(pulsar.getConfiguration().isEnablePersistentTopics());
            localData.setNonPersistentTopicsEnabled(pulsar.getConfiguration().isEnableNonPersistentTopics());
            localData.setLoadManagerClassName(conf.getLoadManagerClassName());

            String brokerId = pulsar.getBrokerId();
            brokerZnodePath = LoadManager.LOADBALANCE_BROKERS_ROOT + "/" + brokerId;
            updateLocalBrokerData();

            brokerDataLock = brokersData.acquireLock(brokerZnodePath, localData).join();
            pulsarResources.getLoadBalanceResources()
                    .getBrokerTimeAverageDataResources()
                    .updateTimeAverageBrokerData(brokerId, new TimeAverageBrokerData())
                    .join();
            updateAll();
        } catch (Exception e) {
            log.error("Unable to acquire lock for broker: [{}]", brokerZnodePath, e);
            throw new PulsarServerException(e);
        }
    }

    /**
     * As any broker, stop the load manager.
     *
     * @throws PulsarServerException
     *             If an unexpected error occurred when attempting to stop the load manager.
     */
    @Override
    public void stop() throws PulsarServerException {
        executors.shutdownNow();

        try {
            brokersData.close();
        } catch (Exception e) {
            log.warn("Failed to release broker lock: {}", e.getMessage());
        }
    }

    /**
     * As any broker, retrieve the namespace bundle stats and system resource usage to update data local to this broker.
     * @return
     */
    @Override
    public LocalBrokerData updateLocalBrokerData() {
        lock.lock();
        try {
            final SystemResourceUsage systemResourceUsage = LoadManagerShared.getSystemResourceUsage(brokerHostUsage);
            localData.update(systemResourceUsage, getBundleStats());
            updateLoadBalancingMetrics(systemResourceUsage);
            if (conf.isExposeBundlesMetricsInPrometheus()) {
                updateLoadBalancingBundlesMetrics(getBundleStats());
            }
        } catch (Exception e) {
            log.warn("Error when attempting to update local broker data", e);
            if (e instanceof ConcurrentModificationException) {
                throw (ConcurrentModificationException) e;
            }
        } finally {
            lock.unlock();
        }
        return localData;
    }

    /**
     * As any broker, update its bundle metrics.
     *
     * @param bundlesData
     */
    private void updateLoadBalancingBundlesMetrics(Map<String, NamespaceBundleStats> bundlesData) {
        List<Metrics> metrics = new ArrayList<>();
        for (Map.Entry<String, NamespaceBundleStats> entry: bundlesData.entrySet()) {
            final String bundle = entry.getKey();
            final NamespaceBundleStats stats = entry.getValue();
            Map<String, String> dimensions = new HashMap<>();
            dimensions.put("broker", pulsar.getAdvertisedAddress());
            dimensions.put("bundle", bundle);
            dimensions.put("metric", "bundle");
            Metrics m = Metrics.create(dimensions);
            m.put("brk_bundle_msg_rate_in", stats.msgRateIn);
            m.put("brk_bundle_msg_rate_out", stats.msgRateOut);
            m.put("brk_bundle_topics_count", stats.topics);
            m.put("brk_bundle_consumer_count", stats.consumerCount);
            m.put("brk_bundle_producer_count", stats.producerCount);
            m.put("brk_bundle_msg_throughput_in", stats.msgThroughputIn);
            m.put("brk_bundle_msg_throughput_out", stats.msgThroughputOut);
            metrics.add(m);
        }
        this.bundleMetrics.set(metrics);
    }

    /**
     * As any broker, update System Resource Usage Percentage.
     *
     * @param systemResourceUsage
     */
    private void updateLoadBalancingMetrics(final SystemResourceUsage systemResourceUsage) {
        List<Metrics> metrics = new ArrayList<>();
        Map<String, String> dimensions = new HashMap<>();

        dimensions.put("broker", pulsar.getAdvertisedAddress());
        dimensions.put("metric", "loadBalancing");

        Metrics m = Metrics.create(dimensions);
        m.put("brk_lb_cpu_usage", systemResourceUsage.getCpu().percentUsage());
        m.put("brk_lb_memory_usage", systemResourceUsage.getMemory().percentUsage());
        m.put("brk_lb_directMemory_usage", systemResourceUsage.getDirectMemory().percentUsage());
        m.put("brk_lb_bandwidth_in_usage", systemResourceUsage.getBandwidthIn().percentUsage());
        m.put("brk_lb_bandwidth_out_usage", systemResourceUsage.getBandwidthOut().percentUsage());
        metrics.add(m);
        this.loadBalancingMetrics.set(metrics);
    }

    /**
     * As any broker, write the local broker data to metadata store.
     */
    @Override
    public void writeBrokerDataOnZooKeeper() {
        writeBrokerDataOnZooKeeper(false);
    }

    @Override
    public void writeBrokerDataOnZooKeeper(boolean force) {
        lock.lock();
        try {
            updateLocalBrokerData();

            // Do not attempt to write if not connected
            if (lastMetadataSessionEvent != null
                    && lastMetadataSessionEvent.isConnected()
                    && (needBrokerDataUpdate() || force)) {
                localData.setLastUpdate(System.currentTimeMillis());

                brokerDataLock.updateValue(localData).join();

                // Clear deltas.
                localData.cleanDeltas();

                // Update previous data.
                lastData.update(localData);
            }
        } catch (Exception e) {
            log.warn("Error writing broker data on metadata store", e);
            if (e instanceof ConcurrentModificationException) {
                throw (ConcurrentModificationException) e;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * sort bundles by load and select topK bundles for each broker.
     * @return the number of bundles selected
     */
    private int selectTopKBundle() {
        bundleArr.clear();
        Set<String> sheddingExcludedNamespaces = conf.getLoadBalancerSheddingExcludedNamespaces();
        for (Map.Entry<String, BundleData> entry : loadData.getBundleData().entrySet()) {
            String bundle = entry.getKey();
            String namespace = NamespaceBundle.getBundleNamespace(bundle);
            if (sheddingExcludedNamespaces.contains(namespace)) {
                continue;
            }
            bundleArr.add(entry);
        }

        int maxNumberOfBundlesInBundleLoadReport = pulsar.getConfiguration()
                .getLoadBalancerMaxNumberOfBundlesInBundleLoadReport();
        if (maxNumberOfBundlesInBundleLoadReport <= 0) {
            // select all bundle
            return bundleArr.size();
        } else {
            // select topK bundle for each broker, so select topK * brokerCount bundle in total
            int brokerCount = Math.max(1, loadData.getBrokerData().size());
            int updateBundleCount = Math.min(maxNumberOfBundlesInBundleLoadReport * brokerCount, bundleArr.size());
            if (updateBundleCount == 0) {
                // no bundle to update
                return 0;
            }
            TopKBundles.partitionSort(bundleArr, updateBundleCount);
            return updateBundleCount;
        }
    }

    /**
     * As the leader broker, write bundle data aggregated from all brokers to metadata store.
     */
    @Override
    public void writeBundleDataOnZooKeeper() {
        updateBundleData();
        // Write the bundle data to metadata store.
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // use synchronized to protect bundleArr.
        synchronized (bundleArr) {
            int updateBundleCount = selectTopKBundle();
            bundleArr.stream().limit(updateBundleCount).forEach(entry -> futures.add(
                    pulsarResources.getLoadBalanceResources().getBundleDataResources().updateBundleData(
                            entry.getKey(), (BundleData) entry.getValue())));
        }

        // Write the time average broker data to metadata store.
        for (Map.Entry<String, BrokerData> entry : loadData.getBrokerData().entrySet()) {
            final String broker = entry.getKey();
            final TimeAverageBrokerData data = entry.getValue().getTimeAverageData();
            futures.add(pulsarResources.getLoadBalanceResources()
                    .getBrokerTimeAverageDataResources().updateTimeAverageBrokerData(broker, data));
        }

        try {
            FutureUtil.waitForAll(futures).join();
        } catch (Exception e) {
            log.warn("Error when writing metadata data to store", e);
        }
    }

    private void deleteBundleDataFromMetadataStore(String bundle) {
        try {
            pulsarResources.getLoadBalanceResources().getBundleDataResources().deleteBundleData(bundle).join();
        } catch (Exception e) {
            if (!(e.getCause() instanceof NotFoundException)) {
                log.warn("Failed to delete bundle-data {} from metadata store", bundle, e);
            }
        }
    }

    private void deleteTimeAverageDataFromMetadataStoreAsync(String broker) {
        pulsarResources.getLoadBalanceResources()
                .getBrokerTimeAverageDataResources().deleteTimeAverageBrokerData(broker).whenComplete((__, ex) -> {
                    if (ex != null && !(ex.getCause() instanceof MetadataStoreException.NotFoundException)) {
                        log.warn("Failed to delete dead broker {} time "
                                + "average data from metadata store", broker, ex);
                    }
                });
    }

    @Override
    public LocalBrokerData getBrokerLocalData(String broker) {
        String key = String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, broker);
        try {
            return brokersData.readLock(key).join().orElse(null);
        } catch (Exception e) {
            log.warn("Failed to get local-broker data for {}", broker, e);
            return null;
        }
    }

    @Override
    public List<Metrics> getLoadBalancingMetrics() {
        List<Metrics> metricsCollection = new ArrayList<>();

        if (this.loadBalancingMetrics.get() != null) {
            metricsCollection.addAll(this.loadBalancingMetrics.get());
        }

        if (this.bundleUnloadMetrics.get() != null) {
            metricsCollection.addAll(this.bundleUnloadMetrics.get());
        }

        if (this.bundleSplitMetrics.get() != null) {
            metricsCollection.addAll(this.bundleSplitMetrics.get());
        }

        if (this.bundleMetrics.get() != null) {
            metricsCollection.addAll(this.bundleMetrics.get());
        }

        return metricsCollection;
    }

    @Override
    public String setNamespaceBundleAffinity(String bundle, String broker) {
        if (StringUtils.isBlank(broker)) {
            return this.bundleBrokerAffinityMap.remove(bundle);
        }
        return this.bundleBrokerAffinityMap.put(bundle, broker);
    }
}
