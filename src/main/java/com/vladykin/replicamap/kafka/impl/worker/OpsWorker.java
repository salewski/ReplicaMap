package com.vladykin.replicamap.kafka.impl.worker;

import com.vladykin.replicamap.ReplicaMapException;
import com.vladykin.replicamap.kafka.impl.msg.OpMessage;
import com.vladykin.replicamap.kafka.impl.util.Box;
import com.vladykin.replicamap.kafka.impl.util.FlushQueue;
import com.vladykin.replicamap.kafka.impl.util.Utils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.vladykin.replicamap.base.ReplicaMapBase.OP_FLUSH_NOTIFICATION;
import static com.vladykin.replicamap.base.ReplicaMapBase.OP_FLUSH_REQUEST;
import static com.vladykin.replicamap.base.ReplicaMapBase.OP_PUT;
import static com.vladykin.replicamap.base.ReplicaMapBase.OP_REMOVE_ANY;
import static com.vladykin.replicamap.kafka.impl.util.Utils.isOverMaxOffset;
import static com.vladykin.replicamap.kafka.impl.util.Utils.millis;
import static java.util.Collections.singleton;

/**
 * Polls the `ops` topic and applies the updates to the inner map.
 *
 * @author Sergi Vladykin http://vladykin.com
 */
public class OpsWorker extends Worker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OpsWorker.class);

    protected static final ConsumerRecord<Object,OpMessage> NOT_FOUND = new ConsumerRecord<>("NOT_FOUND", 0, 0, null, null);
    protected static final ConsumerRecord<Object,OpMessage> NOT_EXIST = new ConsumerRecord<>("NOT_EXIST", 0, 0, null, null);

    protected final long clientId;

    protected final String dataTopic;
    protected final String opsTopic;
    protected final String flushTopic;

    protected final Set<Integer> assignedParts;

    protected final Consumer<Object,Object> dataConsumer;
    protected final Consumer<Object,OpMessage> opsConsumer;
    protected final Producer<Object,OpMessage> flushProducer;

    protected final int flushPeriodOps;
    protected final List<FlushQueue> flushQueues;
    protected final Queue<ConsumerRecord<Object,OpMessage>> cleanQueue;

    protected final OpsUpdateHandler updateHandler;

    protected final CompletableFuture<Void> steadyFut = new CompletableFuture<>();
    protected Map<TopicPartition,Long> endOffsetsOps;
    protected int maxAllowedSteadyLag;

    protected final Map<TopicPartition,OpMessage> lastFlushNotifications = new HashMap<>();

    public OpsWorker(
        long clientId,
        String dataTopic,
        String opsTopic,
        String flushTopic,
        int workerId,
        Set<Integer> assignedParts,
        Consumer<Object,Object> dataConsumer,
        Consumer<Object,OpMessage> opsConsumer,
        Producer<Object,OpMessage> flushProducer,
        int flushPeriodOps,
        List<FlushQueue> flushQueues,
        Queue<ConsumerRecord<Object,OpMessage>> cleanQueue,
        OpsUpdateHandler updateHandler
    ) {
        super("replicamap-ops-" + dataTopic + "-" +
            Long.toHexString(clientId), workerId);

        this.clientId = clientId;
        this.dataTopic = dataTopic;
        this.opsTopic = opsTopic;
        this.flushTopic = flushTopic;
        this.dataConsumer = dataConsumer;
        this.opsConsumer = opsConsumer;
        this.flushProducer = flushProducer;
        this.assignedParts = assignedParts;
        this.flushPeriodOps = flushPeriodOps;
        this.flushQueues = flushQueues;
        this.cleanQueue = cleanQueue;
        this.updateHandler = updateHandler;
    }

    protected Map<TopicPartition, Long> loadData() {
        try {
            Duration pollTimeout = millis(1);
            Map<TopicPartition,Long> opsOffsets = new HashMap<>();

            for (Integer part : assignedParts) {
                TopicPartition dataPart = new TopicPartition(dataTopic, part);
                log.debug("Loading data for partition {}", dataPart);

                TopicPartition opsPart = new TopicPartition(opsTopic, part);
                ConsumerRecord<Object,OpMessage> lastFlushRec = findLastFlushRecord(dataPart, opsPart, pollTimeout, null);

                long flushOffsetOps = 0L;

                if (lastFlushRec != null) {
                    OpMessage op = lastFlushRec.value();
                    flushOffsetOps = op.getFlushOffsetOps() + 1; // + 1 because we need the first unflushed ops offset
                    long flushOffsetData = op.getFlushOffsetData();

                    if (log.isDebugEnabled())
                        log.debug("Found last flush record {} for partition {}", lastFlushRec, opsPart);

                    loadDataForPartition(dataPart, flushOffsetData, pollTimeout);
                    lastFlushNotifications.put(opsPart, op);
                }
                else
                    log.debug("Flush record does not exist for partition {}", opsPart);

                opsOffsets.put(opsPart, flushOffsetOps);

                checkInterrupted();
            }

            return opsOffsets;
        }
        finally {
            Utils.close(dataConsumer); // We do not need it anymore.
        }
    }

    protected int loadDataForPartition(TopicPartition dataPart, long flushOffsetData, Duration pollTimeout) {
        dataConsumer.assign(singleton(dataPart));
        dataConsumer.seekToBeginning(singleton(dataPart));

        int loadedRecs = 0;
        long lastRecOffset = -1;

        outer: for (;;) {
            ConsumerRecords<Object,Object> recs = dataConsumer.poll(pollTimeout);

            if (recs.isEmpty()) {
                long endOffsetData = Utils.endOffset(dataConsumer, dataPart);

                if (log.isDebugEnabled()) {
                    log.debug( "Empty records while loading data for partition {}, endOffsetData: {}, " +
                            "flushOffsetData: {}, loadedRecs: {}, lastRecOffset: {}",
                        dataPart, endOffsetData, flushOffsetData, loadedRecs, lastRecOffset);
                }

                if (endOffsetData <= flushOffsetData) { // flushOffsetData is inclusive, endOffsetData is exclusive
                    throw new ReplicaMapException("Too low end offset of the data partition: " +
                        dataPart + ", endOffsetData: " + endOffsetData + ", flushOffsetData: " + flushOffsetData);
                }

                if (dataConsumer.position(dataPart) == endOffsetData)
                    break; // we've loaded all the available data records
            }
            else {
                assert recs.partitions().size() == 1;

                for (ConsumerRecord<Object,Object> rec : recs.records(dataPart)) {
                    if (isOverMaxOffset(rec, flushOffsetData))
                        break outer;

                    loadedRecs++;
                    lastRecOffset = rec.offset();

                    log.trace("Loading data partition {}, record: {}", dataPart, rec);

                    applyDataTopicRecord(rec);

                    if (rec.offset() == flushOffsetData)
                        break outer; // it was the last record we need
                }
            }
        }

        if (log.isDebugEnabled())
            log.debug("Loaded {} data records for partition {}", loadedRecs, dataPart);

        return loadedRecs;
    }

    protected void applyDataTopicRecord(ConsumerRecord<Object,Object> dataRec) {
        Object key = dataRec.key();
        Object val = dataRec.value();
        byte opType = val == null ? OP_REMOVE_ANY : OP_PUT;

        updateHandler.applyReceivedUpdate(0L, 0L, opType, key, null, val, null, null);
    }

    protected void applyOpsTopicRecords(TopicPartition opsPart, List<ConsumerRecord<Object,OpMessage>> partRecs) {
        FlushQueue flushQueue = flushQueues.get(opsPart.partition());

        int lastIndex = partRecs.size() - 1;
        Box<Object> updatedValueBox = new Box<>();

        for (int i = 0; i <= lastIndex; i++) {
            updatedValueBox.clear();
            ConsumerRecord<Object,OpMessage> rec = partRecs.get(i);

            if (log.isTraceEnabled())
                log.trace("Applying op to partition {}, steady: {}, record: {}", opsPart, isSteady(), rec);

            Object key = rec.key();
            OpMessage op = rec.value();
            long opClientId = op.getClientId();
            byte opType = op.getOpType();

            boolean updated = false;
            boolean needClean = false;
            boolean needFlush = opClientId == clientId && rec.offset() > 0 && rec.offset() % flushPeriodOps == 0;

            if (key == null) {
                if (opType == OP_FLUSH_NOTIFICATION) {
                    OpMessage old = lastFlushNotifications.get(opsPart);
                    // Notifications can arrive out of order, just ignore the outdated ones.
                    if (old == null || old.getFlushOffsetOps() < op.getFlushOffsetOps()) {
                        // If someone else has successfully flushed the data, we need to cleanup our flush queue.
                        needClean = opClientId != clientId;
                        lastFlushNotifications.put(opsPart, op);
                        log.debug("Received flush notification: {}", rec);
                    }
                }
                else // Forward compatibility: there are may be new message types.
                    log.warn("Unexpected op type: {}", (char)op.getOpType());
            }
            else {
                updated = updateHandler.applyReceivedUpdate(
                    opClientId,
                    op.getOpId(),
                    opType,
                    key,
                    op.getExpectedValue(),
                    op.getUpdatedValue(),
                    op.getFunction(),
                    updatedValueBox);
            }

            flushQueue.add(
                key,
                updatedValueBox.get(),
                rec.offset(),
                updated,
                needClean || needFlush || i == lastIndex);

            if (needFlush) {
                OpMessage lastFlush = lastFlushNotifications.get(opsPart);
                long lastCleanOffsetOps = lastFlush == null ? -1L : lastFlush.getFlushOffsetOps();
                sendFlushRequest(opsPart.partition(), rec.offset(), lastCleanOffsetOps);
            }
            else if (needClean)
                sendCleanRequest(rec);
        }
    }

    protected void sendCleanRequest(ConsumerRecord<Object,OpMessage> rec) {
        log.debug("Sending clean request: {}", rec);
        cleanQueue.add(rec);
    }

    protected void sendFlushRequest(int part, long flushOffsetOps, long lastCleanOffsetOps) {
        ProducerRecord<Object,OpMessage> rec = new ProducerRecord<>(flushTopic, part,
            null, newFlushRequestOpMessage(flushOffsetOps, lastCleanOffsetOps));
        log.debug("Sending flush request: {}", rec);
        flushProducer.send(rec);
    }

    protected OpMessage newFlushRequestOpMessage(long flushOffsetOps, long lastCleanOffsetOps) {
        return new OpMessage(OP_FLUSH_REQUEST, clientId, 0L, flushOffsetOps, lastCleanOffsetOps);
    }

    protected ConsumerRecord<Object,OpMessage> findLastFlushRecord(
        TopicPartition dataPart,
        TopicPartition opsPart,
        Duration pollTimeout,
        Runnable testCallback
    ) {
        opsConsumer.assign(singleton(opsPart));
        long maxOffset = Utils.endOffset(opsConsumer, opsPart);

        for (;;) {
            ConsumerRecord<Object,OpMessage> lastFlushRec =
                tryFindLastFlushRecord(opsPart, maxOffset, pollTimeout);

            if (lastFlushRec == NOT_EXIST)
                return null;

            if (lastFlushRec != NOT_FOUND && isValidFlushRecord(dataPart, lastFlushRec))
                return lastFlushRec;

            maxOffset -= flushPeriodOps;

            if (testCallback != null)
                testCallback.run();
        }
    }

    protected boolean isValidFlushRecord(TopicPartition dataPart, ConsumerRecord<Object,OpMessage> lastFlushRec) {
        // Sometimes Kafka provides smaller offset than actually is known to be committed.
        // In that case we have to find earlier flush record to be able to load the data.
        long endOffsetData = Utils.endOffset(dataConsumer, dataPart);
        if (endOffsetData > lastFlushRec.value().getFlushOffsetData())
            return true;

        log.warn("Committed offset is not found in data partition {}, end offset: {}, flush record: {}",
            dataPart, endOffsetData, lastFlushRec);

        return false;
    }

    protected ConsumerRecord<Object,OpMessage> tryFindLastFlushRecord(
        TopicPartition opsPart,
        long maxOffset,
        Duration pollTimeout
    ) {
        long offset = maxOffset - flushPeriodOps;
        if (offset < 0)
            offset = 0;

        if (log.isDebugEnabled()) {
            log.debug("Searching for the last flush notification for partition {}, seek to {}, flushPeriodOps: {}",
                opsPart, offset, flushPeriodOps);
        }

        opsConsumer.seek(opsPart, offset);

        int processedRecs = 0;
        ConsumerRecord<Object,OpMessage> lastFlushRec = null;

        outer: for (;;) {
            ConsumerRecords<Object,OpMessage> recs = opsConsumer.poll(pollTimeout);

            if (recs.isEmpty()) {
                if (Utils.isEndPosition(opsConsumer, opsPart))
                    break;
                else
                    continue;
            }

            for (ConsumerRecord<Object,OpMessage> rec : recs.records(opsPart)) {
                processedRecs++;

                if (log.isTraceEnabled())
                    log.trace("Searching through record: {}", rec);

                if (rec.value().getOpType() == OP_FLUSH_NOTIFICATION) {
                    lastFlushRec = rec;
                    break outer;
                }

                if (rec.offset() > maxOffset)
                    break outer;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Searched through {} records in partition {}, last flush notification record: {}",
                processedRecs, opsPart, lastFlushRec);
        }

        if (lastFlushRec != null)
            return lastFlushRec;

        return offset == 0 ? NOT_EXIST : NOT_FOUND;
    }

    protected void seekOpsOffsets(Map<TopicPartition,Long> opsOffsets) {
        assert opsOffsets.size() == assignedParts.size();
        opsConsumer.assign(opsOffsets.keySet());

        for (Map.Entry<TopicPartition,Long> entry : opsOffsets.entrySet()) {
            TopicPartition part = entry.getKey();
            long offset = entry.getValue();

            if (log.isDebugEnabled())
                log.debug("Seek ops consumer to {} for partition {}", offset, part);

            opsConsumer.seek(part, offset);

            checkInterrupted();
        }
    }

    protected void processOps() {
        Duration pollTimeout = Utils.millis(3);

        while (!isInterrupted()) {
            ConsumerRecords<Object,OpMessage> recs;
            try {
                recs = opsConsumer.poll(pollTimeout);
            }
            catch (InterruptException | WakeupException e) {
                log.debug("Poll interrupted for topic {}", opsTopic);
                return;
            }

            if (processOpsRecords(recs)) {
                log.debug("Steady for partitions: {}", assignedParts);
                pollTimeout = Utils.seconds(3);
            }
        }
    }

    protected boolean processOpsRecords(ConsumerRecords<Object,OpMessage> recs) {
        for (TopicPartition part : recs.partitions())
            applyOpsTopicRecords(part, recs.records(part));

        return !isSteady() && isActuallySteady() && markSteady();
    }

    protected boolean isSteady() {
        return steadyFut.isDone();
    }

    protected boolean markSteady() {
        return steadyFut.complete(null);
    }

    protected boolean isActuallySteady() {
        boolean freshEndOffsetsFetched = false;

        for (;;) {
            if (endOffsetsOps == null) {
                Set<TopicPartition> parts = opsConsumer.assignment();
                endOffsetsOps = opsConsumer.endOffsets(parts);
                freshEndOffsetsFetched = true;
            }

            long totalLag = 0;

            for (Map.Entry<TopicPartition,Long> entry : endOffsetsOps.entrySet()) {
                long endOffset = entry.getValue();
                TopicPartition part = entry.getKey();

                totalLag += endOffset - opsConsumer.position(part);
            }

            if (totalLag <= maxAllowedSteadyLag) {
                // we either need to refresh offsets and check the lag once again
                // or just cleanup before returning true
                endOffsetsOps = null;

                if (freshEndOffsetsFetched)
                    return true; // it was freshly fetched offsets, we are really steady

                // Initially maxAllowedSteadyLag is 0 to make sure
                // that we've achieved at least the first fetched endOffsetsOps.
                // This is needed in the case when we have only a single manager, in a single thread
                // we do a few updates, stop the manager and restart it. We expect to see all of
                // our updates to be in place when start operation completes but if
                // maxAllowedSteadyLag is non-zero we may have some updates lagged.
                // This behavior looks counter-intuitive from the user perspective
                // because it breaks program order. Thus, we must fetch all the
                // operations we aware of at the moment of start to safely complete steadyFut.
                maxAllowedSteadyLag = flushPeriodOps;
                continue;
            }
            return false;
        }
    }

    @Override
    protected void doRun() {
        try {
            Map<TopicPartition,Long> opsOffsets = loadData();
            seekOpsOffsets(opsOffsets);
            processOps();
        }
        catch (Exception e) {
            // If the future is completed already, nothing will happen.
            steadyFut.completeExceptionally(e);

            if (!Utils.isInterrupted(e))
                throw new ReplicaMapException(e);
        }
    }

    public CompletableFuture<Void> getSteadyFuture() {
        return steadyFut;
    }

    @Override
    protected void interruptThread() {
        Utils.wakeup(dataConsumer);
        Utils.wakeup(opsConsumer);

        super.interruptThread();
    }

    @Override
    public void close() {
        Utils.close(dataConsumer);
        Utils.close(opsConsumer);
    }
}
