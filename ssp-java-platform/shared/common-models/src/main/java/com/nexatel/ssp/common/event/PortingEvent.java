package com.nexatel.ssp.common.event;

import com.nexatel.ssp.common.dto.PortingRequestDto;
import lombok.*;

import java.time.Instant;

/**
 * Porting domain event published to AWS SQS.
 * Consumed by notification-service and fallout-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortingEvent {

    public enum EventType {
        PORT_INITIATED,
        PORT_STATUS_SUBMITTED,
        PORT_STATUS_IN_PROGRESS,
        PORT_STATUS_COMPLETED,
        PORT_STATUS_FAILED,
        PORT_STATUS_FALLOUT,
        PORT_STATUS_CANCELLED,
        FALLOUT_CREATED,
        FALLOUT_RESOLVED
    }

    private EventType eventType;
    private PortingRequestDto payload;
    private String requestId;
    private Instant timestamp;
    private String sourceService;
}
