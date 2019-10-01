package com.ibm.streamsx.kafka.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.types.Blob;
import com.ibm.streams.operator.types.ValueFactory;
import com.ibm.streamsx.kafka.test.utils.Constants;
import com.ibm.streamsx.kafka.test.utils.Delay;
import com.ibm.streamsx.kafka.test.utils.KafkaSPLStreamsUtils;
import com.ibm.streamsx.topology.TStream;
import com.ibm.streamsx.topology.Topology;
import com.ibm.streamsx.topology.context.StreamsContext;
import com.ibm.streamsx.topology.context.StreamsContext.Type;
import com.ibm.streamsx.topology.context.StreamsContextFactory;
import com.ibm.streamsx.topology.function.BiFunction;
import com.ibm.streamsx.topology.spl.SPL;
import com.ibm.streamsx.topology.spl.SPLStream;
import com.ibm.streamsx.topology.spl.SPLStreams;
import com.ibm.streamsx.topology.tester.Condition;
import com.ibm.streamsx.topology.tester.Tester;

/*
 * This is simple green thread test to verify
 * the operators are functioning.
 * 
 * This test requires the following: 
 *  - topic "test" be created on the Kafka server
 *  - appConfig "kafka-test" be created on the domain
 */
public class KafkaOperatorsBlobTypeTest extends AbstractKafkaTest {

    public KafkaOperatorsBlobTypeTest() throws Exception {
        super();
    }

    @Test
    public void kafkaBlobTypeTest() throws Exception {
        Topology topo = getTopology();


        StreamSchema schema = KafkaSPLStreamsUtils.BLOB_SCHEMA;


        // create the producer (produces tuples after a short delay)
        TStream<Blob> srcStream = topo.strings(Constants.STRING_DATA).transform(s -> ValueFactory.newBlob(s.getBytes())).modify(new Delay<>(5000));
        SPLStream splSrcStream = SPLStreams.convertStream(srcStream, new Converter(), schema);
        SPL.invokeSink(Constants.KafkaProducerOp, 
                splSrcStream, 
                getKafkaParams());

        // create the consumer
        SPLStream consumerStream = SPL.invokeSource(topo, Constants.KafkaConsumerOp, getKafkaParams(), schema);
        SPLStream msgStream = SPLStreams.stringToSPLStream(consumerStream.convert(t -> new String(t.getBlob("message").getData())));

        // test the output of the consumer
        StreamsContext<?> context = StreamsContextFactory.getStreamsContext(Type.DISTRIBUTED_TESTER);
        Tester tester = topo.getTester();
        Condition<List<String>> stringContentsUnordered = tester.stringContentsUnordered (msgStream.toStringStream(), Constants.STRING_DATA);
        HashMap<String, Object> config = new HashMap<>();
        //        config.put (ContextProperties.TRACING_LEVEL, java.util.logging.Level.FINE);
        //        config.put(ContextProperties.KEEP_ARTIFACTS,  new Boolean(true));
        tester.complete(context, config, stringContentsUnordered, 60, TimeUnit.SECONDS);

        // check the results
        Assert.assertTrue (stringContentsUnordered.valid());
        Assert.assertTrue (stringContentsUnordered.getResult().size() == Constants.STRING_DATA.length);
    }

    private Map<String, Object> getKafkaParams() {
        Map<String, Object> params = new HashMap<String, Object>();

        params.put("topic", Constants.TOPIC_TEST);
        params.put("appConfigName", Constants.APP_CONFIG);

        return params;
    }

    private static class Converter implements BiFunction<Blob, OutputTuple, OutputTuple> {
        private static final long serialVersionUID = 1L;
        private int counter = 0;

        @Override
        public OutputTuple apply(Blob val, OutputTuple outTuple) {
            outTuple.setBlob("key", ValueFactory.newBlob(String.valueOf(counter++).getBytes()));
            outTuple.setBlob("message", val);
            return outTuple;
        }
    }
}
