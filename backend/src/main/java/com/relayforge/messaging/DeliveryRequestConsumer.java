package com.relayforge.messaging;

import com.relayforge.config.WorkerRole;
import com.relayforge.delivery.AttemptKind;
import com.relayforge.delivery.DeliveryExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes delivery requests and drives one attempt each.
 *
 * <h2>The acknowledgement contract</h2>
 *
 * The offset is committed only after {@link DeliveryExecutor#execute} returns —
 * that is, after the attempt's outcome has been durably written. This is the
 * mechanism behind the platform's at-least-once guarantee:
 *
 * <ul>
 *   <li>Crash <em>before</em> the ack: the message is redelivered. The delivery
 *       is either still claimable (so it is retried) or already resolved (so the
 *       conditional claim rejects it). Either way, correct.
 *   <li>Acknowledge <em>before</em> processing: a crash loses the message
 *       entirely and the delivery sits until the dispatcher's requeue grace
 *       elapses — turning a guarantee into a delay.
 * </ul>
 *
 * <h2>Why a failure is still acknowledged</h2>
 *
 * When {@code execute} throws, the message <em>is</em> acknowledged. Not
 * acknowledging would redeliver the same message immediately and forever —
 * blocking its partition, and with it every other endpoint mapped to that
 * partition. The delivery row is the authoritative schedule, so the correct
 * recovery is to leave it to the lease reconciler and the dispatcher, which will
 * re-enqueue it on a sane timetable. The message is also forwarded to the
 * dead-letter topic so the failure is inspectable rather than merely logged.
 *
 * <p>In short: Kafka's job is to hand work out; PostgreSQL's job is to remember
 * what still needs doing. Blocking a partition to protect a single delivery
 * would trade one delivery's progress for everyone else's.
 */
@Component
@WorkerRole
public class DeliveryRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRequestConsumer.class);

    private final DeliveryExecutor executor;
    private final KafkaTemplate<String, DeliveryRequest> kafkaTemplate;
    private final Timer queueLagTimer;
    private final Timer processingTimer;

    public DeliveryRequestConsumer(
            DeliveryExecutor executor,
            KafkaTemplate<String, DeliveryRequest> kafkaTemplate,
            MeterRegistry meters) {
        this.executor = executor;
        this.kafkaTemplate = kafkaTemplate;
        this.queueLagTimer =
                Timer.builder("relayforge.delivery.queue.lag")
                        .description(
                                "Time between a delivery being enqueued and a worker starting it")
                        .publishPercentileHistogram()
                        .register(meters);
        this.processingTimer =
                Timer.builder("relayforge.delivery.processing.duration")
                        .description("Total time a worker spent handling one delivery message")
                        .publishPercentileHistogram()
                        .register(meters);
    }

    @KafkaListener(
            topics = Topics.DELIVERY_REQUESTS,
            groupId = "${relayforge.kafka.delivery-group:relayforge-delivery-workers}",
            containerFactory = "deliveryListenerContainerFactory")
    public void onDeliveryRequest(
            ConsumerRecord<String, DeliveryRequest> record, Acknowledgment acknowledgment) {

        DeliveryRequest request = record.value();
        if (request == null || request.deliveryId() == null) {
            // ErrorHandlingDeserializer turns a malformed payload into a null
            // value rather than an exception in the poll loop. Acknowledge it so
            // the poison record cannot block its partition.
            log.error(
                    "Received an unreadable delivery request at {}-{} offset {}; acknowledging to"
                            + " avoid blocking the partition",
                    record.topic(),
                    record.partition(),
                    record.offset());
            acknowledgment.acknowledge();
            return;
        }

        long startNanos = System.nanoTime();
        MDC.put("deliveryId", request.deliveryId().toString());
        MDC.put("tenantId", String.valueOf(request.tenantId()));
        try {
            long lagMillis = System.currentTimeMillis() - request.enqueuedAtEpochMs();
            if (lagMillis >= 0) {
                queueLagTimer.record(Duration.ofMillis(lagMillis));
            }

            DeliveryExecutor.Result result =
                    executor.execute(request.deliveryId(), AttemptKind.AUTOMATIC);
            if (result == DeliveryExecutor.Result.SKIPPED_NOT_CLAIMABLE) {
                log.trace(
                        "Delivery {} was not claimable — duplicate message or already resolved",
                        request.deliveryId());
            }
        } catch (RuntimeException e) {
            log.error(
                    "Unhandled failure while processing delivery {}. Acknowledging the message and"
                            + " leaving recovery to the lease reconciler, so this partition is not"
                            + " blocked.",
                    request.deliveryId(),
                    e);
            forwardToDeadLetterTopic(record, e);
        } finally {
            processingTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
            acknowledgment.acknowledge();
            MDC.remove("deliveryId");
            MDC.remove("tenantId");
        }
    }

    /**
     * Copies an unprocessable message to the dead-letter topic.
     *
     * <p>Best-effort. If forwarding itself fails there is nothing further to try,
     * and the delivery row remains the authoritative record of outstanding work.
     */
    private void forwardToDeadLetterTopic(
            ConsumerRecord<String, DeliveryRequest> record, RuntimeException cause) {
        try {
            kafkaTemplate.send(Topics.DELIVERY_DLT, record.key(), record.value());
        } catch (RuntimeException e) {
            log.warn(
                    "Could not forward delivery {} to the dead-letter topic",
                    record.value() == null ? "unknown" : record.value().deliveryId(),
                    e);
        }
    }
}
