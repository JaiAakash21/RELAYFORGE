package com.relayforge.config;

import com.relayforge.messaging.DeliveryRequest;
import com.relayforge.messaging.Topics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka producer, consumer and topic configuration.
 *
 * <p>Several of these settings are the difference between a pipeline that loses
 * events under load and one that does not; each is annotated with what it buys.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic deliveryRequestsTopic() {
        return TopicBuilder.name(Topics.DELIVERY_REQUESTS)
                // Partitions set the ceiling on consumer parallelism — one
                // partition can only ever be read by one consumer in a group. Six
                // allows scaling to six worker instances without a repartition,
                // which is ample for this project and keeps the dev broker light.
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deliveryDeadLetterTopic() {
        return TopicBuilder.name(Topics.DELIVERY_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, DeliveryRequest> deliveryProducerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> props =
                new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Wait for all in-sync replicas. With acks=1 a leader crash immediately
        // after acknowledging silently loses the message — and the outbox would
        // have already been marked PUBLISHED on the strength of that ack.
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // Idempotent producer: the broker de-duplicates retried sends, so an
        // internal retry cannot turn one publish into several.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        // Bounded publish time. The outbox publisher blocks on the ack, so an
        // unbounded timeout would stall the whole relay behind one bad broker.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, DeliveryRequest> deliveryKafkaTemplate(
            ProducerFactory<String, DeliveryRequest> factory) {
        KafkaTemplate<String, DeliveryRequest> template = new KafkaTemplate<>(factory);
        // Emits producer spans so a trace spans API → outbox → Kafka → worker.
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ConsumerFactory<String, DeliveryRequest> deliveryConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> props =
                new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Wrapping the value deserializer means a malformed message surfaces as a
        // handled error for one record instead of throwing inside the consumer
        // loop, which would otherwise spin forever on the same poison record.
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DeliveryRequest.class);
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.relayforge.messaging");

        // Offsets are committed by the container only after the listener returns,
        // so a crash mid-delivery replays the message rather than skipping it.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Earliest, so a brand-new consumer group drains the existing backlog
        // instead of ignoring everything enqueued before it started.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // A small fetch keeps each poll's work bounded, so the processing time of
        // one batch stays comfortably inside max.poll.interval.ms even when every
        // delivery in it is a slow receiver hitting its full timeout.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DeliveryRequest>
            deliveryListenerContainerFactory(
                    ConsumerFactory<String, DeliveryRequest> consumerFactory,
                    RelayForgeProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, DeliveryRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Bounded concurrency: never more consumer threads than partitions, since
        // the extras would idle, and never more than the configured budget.
        factory.setConcurrency(Math.min(properties.delivery().concurrency(), 6));

        // MANUAL_IMMEDIATE: the listener acknowledges explicitly, and only after
        // the attempt's result has been durably written. This is the point where
        // "at-least-once processing" is actually implemented — acknowledging
        // before the write would make it at-most-once and lose deliveries on
        // crash.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
