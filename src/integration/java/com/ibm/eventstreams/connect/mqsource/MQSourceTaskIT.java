/**
 * Copyright 2022 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.ibm.eventstreams.connect.mqsource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.Message;
import javax.jms.TextMessage;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.After;
import org.junit.Test;

public class MQSourceTaskIT extends AbstractJMSContextIT {

    private MQSourceTask connectTask = null;

    @After
    public void cleanup() throws InterruptedException {
        SourceTaskStopper stopper = new SourceTaskStopper(connectTask);
        stopper.run();
    }


    private static final String MQ_QUEUE = "DEV.QUEUE.1";

    private Map<String, String> createDefaultConnectorProperties() {
        Map<String, String> props = new HashMap<>();
        props.put("mq.queue.manager", getQmgrName());
        props.put("mq.connection.mode", "client");
        props.put("mq.connection.name.list", getConnectionName());
        props.put("mq.channel.name", getChannelName());
        props.put("mq.queue", MQ_QUEUE);
        props.put("mq.user.authentication.mqcsp", "false");
        return props;
    }


    @Test
    public void verifyJmsTextMessages() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");

        connectTask.start(connectorConfigProps);

        TextMessage message1 = getJmsContext().createTextMessage("hello");
        TextMessage message2 = getJmsContext().createTextMessage("world");
        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message1, message2));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(2, kafkaMessages.size());
        for (SourceRecord kafkaMessage : kafkaMessages) {
            assertNull(kafkaMessage.key());
            assertNull(kafkaMessage.valueSchema());

            connectTask.commitRecord(kafkaMessage);
        }

        assertEquals("hello", kafkaMessages.get(0).value());
        assertEquals("world", kafkaMessages.get(1).value());
    }



    @Test
    public void verifyJmsJsonMessages() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.JsonRecordBuilder");

        connectTask.start(connectorConfigProps);

        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(getJmsContext().createTextMessage(
                "{ " +
                    "\"i\" : " + i +
                "}"));
        }
        putAllMessagesToQueue(MQ_QUEUE, messages);

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(5, kafkaMessages.size());
        for (int i = 0; i < 5; i++) {
            SourceRecord kafkaMessage = kafkaMessages.get(i);
            assertNull(kafkaMessage.key());
            assertNull(kafkaMessage.valueSchema());

            Map<?, ?> value = (Map<?, ?>) kafkaMessage.value();
            assertEquals(Long.valueOf(i), value.get("i"));

            connectTask.commitRecord(kafkaMessage);
        }
    }



    @Test
    public void verifyJmsMessageHeaders() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.jms.properties.copy.to.kafka.headers", "true");

        connectTask.start(connectorConfigProps);

        TextMessage message = getJmsContext().createTextMessage("helloworld");
        message.setStringProperty("teststring", "myvalue");
        message.setIntProperty("volume", 11);
        message.setDoubleProperty("decimalmeaning", 42.0);

        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(1, kafkaMessages.size());
        SourceRecord kafkaMessage = kafkaMessages.get(0);
        assertNull(kafkaMessage.key());
        assertNull(kafkaMessage.valueSchema());

        assertEquals("helloworld", kafkaMessage.value());

        assertEquals("myvalue", kafkaMessage.headers().lastWithName("teststring").value());
        assertEquals("11", kafkaMessage.headers().lastWithName("volume").value());
        assertEquals("42.0", kafkaMessage.headers().lastWithName("decimalmeaning").value());

        connectTask.commitRecord(kafkaMessage);
    }



    @Test
    public void verifyMessageBatchIndividualCommits() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.batch.size", "10");

        connectTask.start(connectorConfigProps);

        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            messages.add(getJmsContext().createTextMessage("batch message " + i));
        }
        putAllMessagesToQueue(MQ_QUEUE, messages);

        int nextExpectedMessage = 1;

        List<SourceRecord> kafkaMessages;

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord kafkaMessage : kafkaMessages) {
            assertEquals("batch message " + (nextExpectedMessage++), kafkaMessage.value());
            connectTask.commitRecord(kafkaMessage);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord kafkaMessage : kafkaMessages) {
            assertEquals("batch message " + (nextExpectedMessage++), kafkaMessage.value());
            connectTask.commitRecord(kafkaMessage);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord kafkaMessage : kafkaMessages) {
            assertEquals("batch message " + (nextExpectedMessage++), kafkaMessage.value());
            connectTask.commitRecord(kafkaMessage);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(5, kafkaMessages.size());
        for (SourceRecord kafkaMessage : kafkaMessages) {
            assertEquals("batch message " + (nextExpectedMessage++), kafkaMessage.value());
            connectTask.commitRecord(kafkaMessage);
        }
    }



    @Test
    public void verifyMessageBatchGroupCommits() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.batch.size", "10");

        connectTask.start(connectorConfigProps);

        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            messages.add(getJmsContext().createTextMessage("message " + i));
        }
        putAllMessagesToQueue(MQ_QUEUE, messages);

        List<SourceRecord> kafkaMessages;

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord m : kafkaMessages) {
            connectTask.commitRecord(m);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord m : kafkaMessages) {
            connectTask.commitRecord(m);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(10, kafkaMessages.size());
        for (SourceRecord m : kafkaMessages) {
            connectTask.commitRecord(m);
        }

        kafkaMessages = connectTask.poll();
        assertEquals(5, kafkaMessages.size());
        for (SourceRecord m : kafkaMessages) {
            connectTask.commitRecord(m);
        }
    }



    @Test
    public void verifyMessageIdAsKey() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.record.builder.key.header", "JMSMessageID");

        connectTask.start(connectorConfigProps);

        TextMessage message = getJmsContext().createTextMessage("testmessage");
        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(1, kafkaMessages.size());

        SourceRecord kafkaMessage = kafkaMessages.get(0);
        assertEquals(message.getJMSMessageID().substring("ID:".length()), kafkaMessage.key());
        assertNotNull(message.getJMSMessageID());
        assertEquals(Schema.OPTIONAL_STRING_SCHEMA, kafkaMessage.keySchema());

        assertEquals("testmessage", kafkaMessage.value());

        connectTask.commitRecord(kafkaMessage);
    }



    @Test
    public void verifyCorrelationIdAsKey() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.record.builder.key.header", "JMSCorrelationID");

        connectTask.start(connectorConfigProps);

        TextMessage message1 = getJmsContext().createTextMessage("first message");
        message1.setJMSCorrelationID("verifycorrel");
        TextMessage message2 = getJmsContext().createTextMessage("second message");
        message2.setJMSCorrelationID("ID:5fb4a18030154fe4b09a1dfe8075bc101dfe8075bc104fe4");
        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message1, message2));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(2, kafkaMessages.size());

        SourceRecord kafkaMessage1 = kafkaMessages.get(0);
        assertEquals("verifycorrel", kafkaMessage1.key());
        assertEquals(Schema.OPTIONAL_STRING_SCHEMA, kafkaMessage1.keySchema());
        assertEquals("first message", kafkaMessage1.value());
        connectTask.commitRecord(kafkaMessage1);

        SourceRecord kafkaMessage2 = kafkaMessages.get(1);
        assertEquals("5fb4a18030154fe4b09a1dfe8075bc101dfe8075bc104fe4", kafkaMessage2.key());
        assertEquals(Schema.OPTIONAL_STRING_SCHEMA, kafkaMessage2.keySchema());
        assertEquals("second message", kafkaMessage2.value());
        connectTask.commitRecord(kafkaMessage2);
    }



    @Test
    public void verifyCorrelationIdBytesAsKey() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.record.builder.key.header", "JMSCorrelationIDAsBytes");

        connectTask.start(connectorConfigProps);

        TextMessage message = getJmsContext().createTextMessage("testmessagewithcorrelbytes");
        message.setJMSCorrelationID("verifycorrelbytes");
        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(1, kafkaMessages.size());

        SourceRecord kafkaMessage = kafkaMessages.get(0);
        assertArrayEquals("verifycorrelbytes".getBytes(), (byte[])kafkaMessage.key());
        assertEquals(Schema.OPTIONAL_BYTES_SCHEMA, kafkaMessage.keySchema());

        assertEquals("testmessagewithcorrelbytes", kafkaMessage.value());

        connectTask.commitRecord(kafkaMessage);
    }



    @Test
    public void verifyDestinationAsKey() throws Exception {
        connectTask = new MQSourceTask();

        Map<String, String> connectorConfigProps = createDefaultConnectorProperties();
        connectorConfigProps.put("mq.message.body.jms", "true");
        connectorConfigProps.put("mq.record.builder", "com.ibm.eventstreams.connect.mqsource.builders.DefaultRecordBuilder");
        connectorConfigProps.put("mq.record.builder.key.header", "JMSDestination");

        connectTask.start(connectorConfigProps);

        TextMessage message = getJmsContext().createTextMessage("testmessagewithdest");
        putAllMessagesToQueue(MQ_QUEUE, Arrays.asList(message));

        List<SourceRecord> kafkaMessages = connectTask.poll();
        assertEquals(1, kafkaMessages.size());

        SourceRecord kafkaMessage = kafkaMessages.get(0);
        assertEquals("queue:///" + MQ_QUEUE, kafkaMessage.key());
        assertEquals(Schema.OPTIONAL_STRING_SCHEMA, kafkaMessage.keySchema());

        assertEquals("testmessagewithdest", kafkaMessage.value());

        connectTask.commitRecord(kafkaMessage);
    }
}
