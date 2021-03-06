package com.vladykin.replicamap.kafka;

import com.salesforce.kafka.test.junit5.SharedKafkaTestResource;
import com.vladykin.replicamap.ReplicaMapException;
import com.vladykin.replicamap.holder.MapsHolderSingle;
import com.vladykin.replicamap.kafka.impl.util.LazyList;
import com.vladykin.replicamap.kafka.impl.util.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.ToLongFunction;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.vladykin.replicamap.base.ReplicaMapBaseMultithreadedTest.executeThreads;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.BOOTSTRAP_SERVERS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.DATA_TOPIC;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.FLUSH_PERIOD_OPS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.FLUSH_TOPIC;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.FLUSH_WORKERS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.KEY_DESERIALIZER_CLASS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.KEY_SERIALIZER_CLASS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.MAPS_CHECK_PRECONDITION;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.MAPS_HOLDER;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.OPS_TOPIC;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.OPS_WORKERS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.VALUE_DESERIALIZER_CLASS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerConfig.VALUE_SERIALIZER_CLASS;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerMultithreadedIncrementRestartTest.awaitEqual;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerMultithreadedIncrementSimpleTest.awaitFlushedData;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerSimpleTest.createTopics;
import static com.vladykin.replicamap.kafka.KReplicaMapManagerSimpleTest.kafkaClusterWith3Brokers;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KReplicaMapManagerMultithreadedWindowTest {
    static final String TOPIC_SUFFIX = "_zzz";
    static final String DATA = "data" + TOPIC_SUFFIX;
    static final String OPS = "ops" + TOPIC_SUFFIX;
    static final String FLUSH = "flush" + TOPIC_SUFFIX;

    @RegisterExtension
    public static final SharedKafkaTestResource sharedKafkaTestResource = kafkaClusterWith3Brokers();

    Map<String,Object> getDefaultConfig() {
        HashMap<String,Object> cfg = new HashMap<>();
        cfg.put(BOOTSTRAP_SERVERS, singletonList(sharedKafkaTestResource.getKafkaConnectString()));

        cfg.put(FLUSH_PERIOD_OPS, 3);

        cfg.put(DATA_TOPIC, DATA);
        cfg.put(OPS_TOPIC, OPS);
        cfg.put(FLUSH_TOPIC, FLUSH);

        cfg.put(OPS_WORKERS, 3);
        cfg.put(FLUSH_WORKERS, 2);

        cfg.put(MAPS_HOLDER, AtomicSizeSkipListMapHolder.class);

        cfg.put(KEY_SERIALIZER_CLASS, LongSerializer.class);
        cfg.put(KEY_DESERIALIZER_CLASS, LongDeserializer.class);

        cfg.put(VALUE_SERIALIZER_CLASS, LongSerializer.class);
        cfg.put(VALUE_DESERIALIZER_CLASS, LongDeserializer.class);

        cfg.put(MAPS_CHECK_PRECONDITION, false);

        return cfg;
    }

    @Test
    void testMultithreadedSlidingWindowWithRestart() throws Exception {
        int threadsCnt = 17;
        int managersCnt = 39;
        int maxNonStopCnt = 2;
        int parts = 4;
        int iterations = 10;
        int updatesPerIteration = 200;
        int restartPeriod = 50;

        createTopics(sharedKafkaTestResource,
            DATA, OPS, FLUSH, parts);

        LazyList<KReplicaMapManager> managers = new LazyList<>(managersCnt);
        IntFunction<KReplicaMapManager> managersFactory =  (mgrId) -> {
            KReplicaMapManager manager = new KReplicaMapManager(getDefaultConfig());
            manager.start(120, SECONDS);
            System.out.println("started: " + mgrId + " " +  Long.toHexString(manager.clientId));
            return manager;
        };

        AtomicLong[] lastAddedKey = new AtomicLong[threadsCnt];
        KReplicaMapManager mgr = managers.get(0, managersFactory);

        Map<TopicPartition, Long> offsets = new HashMap<>();
        try (Consumer<Object,Object> dataConsumer = mgr.newKafkaConsumerData()) {
            awaitFlushedData(DATA, dataConsumer, offsets, true);
        }

        for (long k = 0; k < threadsCnt; k++) {
            mgr.getMap().put(k, 1L);
            lastAddedKey[(int)k] = new AtomicLong(k);
        }
        assertEquals(threadsCnt, mgr.getMap().size());

        Random rndx = ThreadLocalRandom.current();
        ExecutorService exec = Executors.newFixedThreadPool(threadsCnt);

        try {
            for (int i = 0; i < iterations; i++) {
                AtomicInteger threadIds = new AtomicInteger();
                CyclicBarrier start = new CyclicBarrier(threadsCnt);

                int nonStopCnt = rndx.nextInt(1 + maxNonStopCnt);
                Set<Integer> nonStop = new HashSet<>();
                for (int k = 0; k < nonStopCnt; k++)
                    nonStop.add(rndx.nextInt(managersCnt));
                System.out.println("managersCnt: " + managersCnt + ",  nonStop: " + new TreeSet<>(nonStop));

                CompletableFuture<?> fut = Utils.allOf(executeThreads(threadsCnt, exec, () -> {
                    Random rnd = ThreadLocalRandom.current();
                    int threadId = threadIds.getAndIncrement();

                    start.await();

                    for (int j = 0; j < updatesPerIteration; j++) {
                        int mgrId = rnd.nextInt(managersCnt);
                        KReplicaMapManager m = managers.get(mgrId, managersFactory);

                        try {
                            // Periodically restart managers, but keep some always running.
                            if (!nonStop.contains(mgrId) && rnd.nextInt(restartPeriod) == 0) {
                                System.out.println("stopping: " + mgrId + " " +  Long.toHexString(m.clientId));
                                managers.reset(mgrId, m);
                                continue;
                            }
                            KReplicaMap<Long,Long> map = m.getMap();

                            long delKey = lastAddedKey[threadId].get();
                            long addKey = delKey + threadsCnt;

                            Long delVal = map.remove(delKey);
                            if (delVal != null)
                                assertEquals(1L, delVal);

                            map.put(addKey, 1L);

                            assertTrue(lastAddedKey[threadId].compareAndSet(delKey, addKey));

//                            if (j % 10 == 0)
//                                System.out.println(map.unwrap());
                        }
                        catch (ReplicaMapException e) {
                            // may happen if another thread concurrently closed our manager
                        }
                    }

                    return null;
                }));

                fut.get(600, SECONDS);

                System.out.println("checking " + i);
                System.out.println("last keys: " + Arrays.asList(lastAddedKey));

                KReplicaMapManager[] ms = new KReplicaMapManager[managersCnt];
                List<KReplicaMap<Long,Long>> maps = new ArrayList<>(managersCnt);
                int minMapSize = Integer.MAX_VALUE;
                int maxMapSize = -1;

                for (int mgrId = 0; mgrId < managersCnt; mgrId++) {
                    KReplicaMapManager m = ms[mgrId] = managers.get(mgrId, managersFactory);

//                    System.out.println(m.getAllowedPartitions());
//                    System.out.println(m.getSentFlushRequests());
//                    System.out.println(m.getAssignedFlushPartitions());
//                    System.out.println(m.getReceivedFlushRequests());
//                    System.out.println(m.getSuccessfulFlushes());
//                    System.out.println();

                    KReplicaMap<Long,Long> map = m.getMap();
                    maps.add(map);

                    int size = map.size();

                    if (size > maxMapSize)
                        maxMapSize = size;

                    if (size < minMapSize)
                        minMapSize = size;
                }

                System.out.println("minMapSize: " + minMapSize + ", maxMapSize: " + maxMapSize);

                // The following assertion is wrong because replication works in multiple threads and
                // "add" operation may be replicated before "remove" if they happen to be in different partitions.
//                assertTrue(maxMapSize <= threadsCnt);

                awaitEqual(maps, (m1, m2) -> {
                    Iterator<Long> it1 = m1.keySet().iterator();
                    Iterator<Long> it2 = m2.keySet().iterator();

                    for (;;) {
                        if (it1.hasNext()) {
                            if (!it2.hasNext())
                                break;
                        }
                        else if (it2.hasNext())
                            break;
                        else
                            return 0;

                        long k1 = it1.next();
                        long k2 = it2.next();

                        if (k1 != k2)
                            break;
                    }

                    // Here we do not actually know which one is greater, we only know they are unequal.
                    // If we provide a consistent comparator, we may hang on a wrong maximum.
                    // Thus we randomize, so that we always make progress.
                    return ThreadLocalRandom.current().nextBoolean() ? -1 : 1;
                }, 120, SECONDS, m -> "\n" + Long.toHexString(m.getManager().clientId) + "  " + m.unwrap().toString());

                awaitPositive(KReplicaMapManager::getSuccessfulFlushes, ms);

                try (Consumer<Object,Object> dataConsumer =
                         managers.get(0, managersFactory).newKafkaConsumerData()) {
                    awaitFlushedData(DATA, dataConsumer, offsets, false);
                }

                System.out.println("iteration " + i + " OK");
            }
        }
//        catch (Throwable e) {
//            e.printStackTrace();
//            fail(e.toString());
//        }
        finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(30, SECONDS));
            Utils.close(managers);
        }
    }

    @SuppressWarnings("BusyWait")
    public static void awaitPositive(ToLongFunction<KReplicaMapManager> metric, KReplicaMapManager... ms)
        throws InterruptedException, TimeoutException {
        long start = System.nanoTime();

        for (;;) {
            long total = 0;

            for (KReplicaMapManager m : ms)
                total += metric.applyAsLong(m);

            if (total > 0)
                return;

            Thread.sleep(100);

            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(30))
                throw new TimeoutException();
        }
    }

    public static class AtomicSizeSkipListMapHolder extends MapsHolderSingle {
        @SuppressWarnings("SortedCollectionWithNonComparableKeys")
        @Override
        protected <K, V> Map<K,V> createInnerMap() {
            // Atomic size calculation to avoid scanning the map contents.
            Map<K,V> map = new ConcurrentSkipListMap<>();
            AtomicInteger size = new AtomicInteger();

            return new Map<K,V>() {
                @Override
                public V get(Object key) {
                    return map.get(key);
                }

                @Override
                public V put(K key, V value) {
                    V old = map.put(key, value);

                    if (old == null)
                        size.incrementAndGet();

                    return old;
                }

                @Override
                public V remove(Object key) {
                    V old = map.remove(key);

                    if (old != null)
                        size.decrementAndGet();

                    return old;
                }

                @Override
                public int size() {
                    return size.get();
                }

                @Override
                public boolean isEmpty() {
                    return size() == 0;
                }

                @Override
                public boolean containsKey(Object key) {
                    return map.containsKey(key);
                }

                @Override
                public boolean containsValue(Object value) {
                    return map.containsValue(value);
                }

                @Override
                public void putAll(Map<? extends K,? extends V> m) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void clear() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Set<K> keySet() {
                    return map.keySet();
                }

                @Override
                public Collection<V> values() {
                    return map.values();
                }

                @Override
                public Set<Entry<K,V>> entrySet() {
                    return map.entrySet();
                }

                @Override
                public String toString() {
                    return "[size=" + size + "]" + map.toString();
                }
            };
        }
    }
}
