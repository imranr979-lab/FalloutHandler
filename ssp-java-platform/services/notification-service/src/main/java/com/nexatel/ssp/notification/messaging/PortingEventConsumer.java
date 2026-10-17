package com.nexatel.ssp.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexatel.ssp.common.event.PortingEvent;
import com.nexatel.ssp.notification.service.TwilioSmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.Map;

/**
 * SQS event consumer for the notification service.
 * Polls the events queue and dispatches SMS notifications
 * based on porting domain events.
 *
 * Uses long-polling (20s) for cost efficiency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortingEventConsumer {

    private final SqsClient sqsClient;
    private final TwilioSmsService twilioSmsService;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.events-queue-url}")
    private String eventsQueueUrl;

    // Event type → SMS event name mapping
    private static final Map<PortingEvent.EventType, String> EVENT_TO_SMS = Map.of(
        PortingEvent.EventType.PORT_STATUS_COMPLETED,   "PORT_IN_COMPLETED",
        PortingEvent.EventType.PORT_STATUS_FAILED,      "PORT_IN_FAILED",
        PortingEvent.EventType.PORT_STATUS_FALLOUT,     "FALLOUT_DETECTED",
        PortingEvent.EventType.FALLOUT_RESOLVED,        "FALLOUT_RESOLVED"
    );

    @Scheduled(fixedDelay = 1000)  // Run every second — SQS long-polling handles wait
    public void pollEvents() {
        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(eventsQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)           // Long polling
                    .messageAttributeNames("EventType")
                    .build();

            ReceiveMessageResponse response = sqsClient.receiveMessage(receiveRequest);
            List<Message> messages = response.messages();

            for (Message message : messages) {
                processMessage(message);
            }

        } catch (Exception e) {
            log.error("SQS polling error: {}", e.getMessage(), e);
        }
    }

    private void processMessage(Message message) {
        String messageId = message.messageId();
        try {
            PortingEvent event = objectMapper.readValue(message.body(), PortingEvent.class);
            log.info("Processing event {} for port request {}",
                    event.getEventType(), event.getPayload().getPortRequestId());

            String smsEvent = EVENT_TO_SMS.get(event.getEventType());
            if (smsEvent != null) {
                String falloutReason = event.getPayload().getFalloutReason();
                twilioSmsService.sendPortingNotification(
                        event.getPayload(), smsEvent, falloutReason);
                log.info("SMS dispatched for event {} port {}",
                        event.getEventType(), event.getPayload().getPortRequestId());
            } else {
                log.debug("No SMS action for event type: {}", event.getEventType());
            }

            // Delete processed message from queue
            deleteMessage(message.receiptHandle());

        } catch (Exception e) {
            log.error("Failed to process SQS message {}: {}", messageId, e.getMessage(), e);
            // Message stays in queue and will be retried (up to maxReceiveCount)
            // then moved to DLQ automatically
        }
    }

    private void deleteMessage(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(eventsQueueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
