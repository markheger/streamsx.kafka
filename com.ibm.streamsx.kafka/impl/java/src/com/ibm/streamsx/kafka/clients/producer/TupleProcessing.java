/*
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.streamsx.kafka.clients.producer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.ibm.streams.operator.Tuple;
import com.ibm.streamsx.kafka.MsgFormatter;
import com.ibm.streamsx.kafka.clients.producer.ErrorCategorizer.ErrCategory;

/**
 * This class represents a pending tuple which is being processed.
 * It is associated with the Tuple from the input port, and one or more {@link RecordProduceAttempt} instances (one for each topic).
 * 
 * @author The IBM Kafka toolkit team
 */
public class TupleProcessing implements RecordProducedHandler, RecordProduceExceptionHandler {

    private static final Logger trace = Logger.getLogger (TupleProcessing.class);
    //    private static final Level DEBUG_LEVEL = SystemProperties.getDebugLevelOverride();
    private static final Level DEBUG_LEVEL = Level.TRACE;
    private static AtomicLong nextSeqNumber = new AtomicLong();

    private final ClientCallback client;
    private final Tuple tuple;
    private final Map <Long, RecordProduceAttempt> producerRecordAttempts;
    private final int initialNumRecords;
    private int nProducedRecords = 0;
    private final long seqNumber;
    private final int maxProducerGenerationsPerRecord;
    private boolean recoverableExcOccurred = false;
    private Exception lastException = null;
    private Set<String> failedTopics = new HashSet<>();
    private final ErrorCategorizer errorCategorizer;

    private TupleProcessing (final Tuple tuple, int nRecords, ClientCallback client, int maxProducerGenerations, final ErrorCategorizer c) {
        this.client = client;
        this.tuple = tuple;
        this.maxProducerGenerationsPerRecord = maxProducerGenerations;
        this.initialNumRecords = nRecords;
        this.seqNumber = nextSeqNumber.incrementAndGet();
        this.producerRecordAttempts = new HashMap<> (nRecords);
        this.errorCategorizer = c == null? new RecoverAllErrors(): c;
    }

    /**
     * Creates a new instance with 1-to-N relation between tuple and producer records.
     */
    public TupleProcessing (final Tuple tuple, List<ProducerRecord<?, ?>> records, int producerGeneration, int maxGenerations, ClientCallback client, ErrorCategorizer cat) {
        this (tuple, records.size(), client, maxGenerations, cat);
        for (ProducerRecord<?, ?> r: records) {
            RecordProduceAttempt p = new RecordProduceAttempt (r, producerGeneration/*, tuple*/);
            p.setProducedHandler (this);
            p.setExceptionHandler (this);
            producerRecordAttempts.put (p.getProducerRecordSeqNumber(), p);
        }
    }

    /**
     * Creates a new instance with 1-to-1 relation between tuple and producer records.
     */
    public TupleProcessing (final Tuple tuple, ProducerRecord<?, ?> record, int producerGeneration, int maxGenerations, ClientCallback client, ErrorCategorizer cat) {
        this (tuple, 1, client, maxGenerations, cat);
        RecordProduceAttempt p = new RecordProduceAttempt (record, producerGeneration/*, tuple*/);
        p.setProducedHandler (this);
        p.setExceptionHandler (this);
        producerRecordAttempts.put (p.getProducerRecordSeqNumber(), p);
    }

    /**
     * @return the seqNumber
     */
    public long getSeqNumber() {
        return seqNumber;
    }

    /**
     * @return the tuple
     */
    public Tuple getTuple() {
        return tuple;
    }

    /**
     * Returns the pending producer records associated with the tuple process
     * as a new Collection instance.
     * @return a new Collection instance with the pending records
     */
    public synchronized Collection <RecordProduceAttempt> getPendingRecords() {
        // create a new collection to avoid ConcurrentModificationException
        return new ArrayList<> (producerRecordAttempts.values());
    }


    /**
     * @see com.ibm.streamsx.kafka.clients.producer.RecordProduceExceptionHandler#onRecordProduceException(long, TopicPartition, Exception, int)
     */
    @Override
    public void onRecordProduceException (long seqNo, TopicPartition tp, Exception e, int nProducerGenerations) {
        final boolean excRecoverable = errorCategorizer.isRecoverable(e) == ErrCategory.RECOVERABLE;
        final boolean recordFinallyFailed = !excRecoverable || nProducerGenerations > maxProducerGenerationsPerRecord;
        trace.warn (MsgFormatter.format ("Producer record {0,number,#} could not be produced for topic partition ''{1}'' with {2,number,#} producer generations: {3}",
                seqNo, tp, nProducerGenerations, e));
        boolean tupleDone = false;
        if (recordFinallyFailed) {
            trace.error (MsgFormatter.format ("Producer record {0,number,#} could finally not be produced for topic partition ''{1}'' with {2,number,#} producer generations: {3}",
                    seqNo, tp, nProducerGenerations, e));
            synchronized (this) {
                this.recoverableExcOccurred |= excRecoverable;
                RecordProduceAttempt r = producerRecordAttempts.remove (seqNo);
                tupleDone = producerRecordAttempts.isEmpty();
                if (r == null) { 
                    trace.warn ("record already removed: " + seqNo);
                }
                else {
                    this.lastException = e;
                    failedTopics.add (tp.topic());
                }
            }
        }
        if (trace.isEnabledFor(DEBUG_LEVEL)) 
            trace.log (DEBUG_LEVEL, MsgFormatter.format ("nProducerGenerations = {0,number,#}; maxProducerGenerationsPerRecord = {1,number,#}; "
                    + "isRecoverable = {2}; finalFail = {3}; producerRecordAttempts.size = {4,number,#}",
                    nProducerGenerations, maxProducerGenerationsPerRecord, excRecoverable, recordFinallyFailed, producerRecordAttempts.size()));
        if (recordFinallyFailed) {
            if (tupleDone) {
                final boolean tryRecover = this.recoverableExcOccurred && maxProducerGenerationsPerRecord == 1;
                client.tupleFailedFinally (this.seqNumber, failedTopics, e, tryRecover);
            }
        }
        else {
            // here we would retry with a new producer instance
            client.tupleFailedTemporarily (this.seqNumber, e);
        }
    }

    /**
     * @see com.ibm.streamsx.kafka.clients.producer.RecordProducedHandler#onRecordProduced(long, ProducerRecord, RecordMetadata)
     */
    @Override
    public void onRecordProduced (long seqNo, ProducerRecord<?, ?> record, RecordMetadata metadata) {
        boolean allRecordsSucceeded = false;
        boolean tupleDone = false;
        synchronized (this) {
            if (producerRecordAttempts.remove (seqNo) == null) {
                // produced callback called twice?
                trace.warn ("record already removed: " + seqNo);
                return;
            }
            ++nProducedRecords;
            if (trace.isEnabledFor(DEBUG_LEVEL))
                trace.log (DEBUG_LEVEL, MsgFormatter.format ("record # {0,number,#} produced @tuple # {1,number,#}. nProducedRecords = {2,number,#}",
                        seqNo, this.seqNumber, nProducedRecords));
            tupleDone = producerRecordAttempts.isEmpty();
            if (tupleDone) {
                allRecordsSucceeded = nProducedRecords == initialNumRecords;
                if (trace.isEnabledFor(DEBUG_LEVEL)) {
                    if (allRecordsSucceeded) {
                        trace.log (DEBUG_LEVEL, MsgFormatter.format ("tuple # {0,number,#} succesfully DONE. Invoking client.tupleProcessed()...", this.seqNumber));
                    }
                    else {
                        trace.log (DEBUG_LEVEL, MsgFormatter.format ("tuple # {0,number,#} DONE with failed records. Invoking client.tupleFailedFinally()...", this.seqNumber));
                    }
                }
            }
        }
        if (tupleDone) {
            if (allRecordsSucceeded) {
                client.tupleProcessed (this.seqNumber);
            }
            else {
                final boolean tryRecover = this.recoverableExcOccurred && maxProducerGenerationsPerRecord == 1;
                client.tupleFailedFinally (this.seqNumber, this.failedTopics, this.lastException, tryRecover);
            }
        }
    }

    public void incrementProducerGenerationCancelTasks() {
        Collection<RecordProduceAttempt> recs = getPendingRecords();
        trace.info ("incrementing producer generation for tuple " + seqNumber + ". #records = " + recs.size());
        for (RecordProduceAttempt rec: recs) {
            rec.incrementProducerGenerationCancelTask();
        }
    }

    public synchronized void addFailedTopic(String topic) {
        this.failedTopics.add (topic);
    }

    public synchronized void setException (Exception e) {
        this.lastException = e;
    }

    public FailureDescription getFailure() {
        return new FailureDescription (failedTopics, lastException);
    }
}
