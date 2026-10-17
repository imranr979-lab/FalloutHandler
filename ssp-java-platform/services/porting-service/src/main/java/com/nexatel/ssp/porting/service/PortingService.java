package com.nexatel.ssp.porting.service;

import com.nexatel.ssp.common.dto.PortingRequestDto;
import com.nexatel.ssp.common.enums.PortingStatus;
import com.nexatel.ssp.common.event.PortingEvent;
import com.nexatel.ssp.porting.entity.PortingRequest;
import com.nexatel.ssp.porting.exception.PortingNotFoundException;
import com.nexatel.ssp.porting.exception.PortingValidationException;
import com.nexatel.ssp.porting.mapper.PortingMapper;
import com.nexatel.ssp.porting.messaging.PortingEventPublisher;
import com.nexatel.ssp.porting.repository.PortingRequestRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic for number porting lifecycle management.
 * Integrates with ClearPort OMS via REST, persists to DynamoDB,
 * and publishes domain events to AWS SQS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortingService {

    private final PortingRequestRepository repository;
    private final PortingEventPublisher eventPublisher;
    private final PortingMapper mapper;
    private final ClearPortIntegrationService clearPortService;

    // ── Initiate porting request ──────────────────────────────────────────────

    public PortingRequestDto initiate(PortingRequestDto dto) {
        log.info("Initiating porting request for TN: {}", dto.getPortedTn());

        // Validate TN not already in progress
        repository.findByPortedTn(dto.getPortedTn()).ifPresent(existing -> {
            if (existing.getStatus() == PortingStatus.PENDING ||
                existing.getStatus() == PortingStatus.SUBMITTED ||
                existing.getStatus() == PortingStatus.IN_PROGRESS) {
                throw new PortingValidationException(
                    "DUPLICATE_REQUEST",
                    "An active porting request already exists for TN: " + dto.getPortedTn()
                );
            }
        });

        String portRequestId = "PR-" + UUID.randomUUID();
        Instant now = Instant.now();

        PortingRequest entity = PortingRequest.builder()
                .portRequestId(portRequestId)
                .portedTn(dto.getPortedTn())
                .customerId(dto.getCustomerId())
                .status(PortingStatus.PENDING)
                .marketCode(dto.getMarketCode())
                .submittedAt(now)
                .updatedAt(now)
                .orderId(dto.getOrderId())
                .clearPortRef(dto.getClearPortRef())
                .build();

        PortingRequest saved = repository.save(entity);

        // Submit to ClearPort OMS
        submitToClearPort(saved);

        PortingRequestDto result = mapper.toDto(saved);

        // Publish PORT_INITIATED event to SQS
        eventPublisher.publish(PortingEvent.builder()
                .eventType(PortingEvent.EventType.PORT_INITIATED)
                .payload(result)
                .requestId(portRequestId)
                .timestamp(now)
                .sourceService("porting-service")
                .build());

        log.info("Porting request initiated: {}", portRequestId);
        return result;
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @Cacheable(value = "portingRequests", key = "#portRequestId")
    public PortingRequestDto getById(String portRequestId) {
        return repository.findById(portRequestId)
                .map(mapper::toDto)
                .orElseThrow(() -> new PortingNotFoundException(portRequestId));
    }

    // ── Get by ported TN ──────────────────────────────────────────────────────

    public PortingRequestDto getByPortedTn(String portedTn) {
        return repository.findByPortedTn(portedTn)
                .map(mapper::toDto)
                .orElseThrow(() -> new PortingNotFoundException("TN: " + portedTn));
    }

    // ── Get by customer ───────────────────────────────────────────────────────

    public List<PortingRequestDto> getByCustomerId(String customerId) {
        return repository.findByCustomerId(customerId)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    // ── Update status ─────────────────────────────────────────────────────────

    @CacheEvict(value = "portingRequests", key = "#portRequestId")
    public PortingRequestDto updateStatus(
            String portRequestId,
            PortingStatus newStatus,
            String falloutReason,
            String clearPortRef) {

        PortingRequest existing = repository.findById(portRequestId)
                .orElseThrow(() -> new PortingNotFoundException(portRequestId));

        validateStatusTransition(existing.getStatus(), newStatus);

        Instant now = Instant.now();
        existing.setStatus(newStatus);
        existing.setUpdatedAt(now);

        if (falloutReason != null)  existing.setFalloutReason(falloutReason);
        if (clearPortRef != null)   existing.setClearPortRef(clearPortRef);
        if (newStatus == PortingStatus.COMPLETED) existing.setCompletedAt(now);

        PortingRequest updated = repository.update(existing);
        PortingRequestDto result = mapper.toDto(updated);

        // Publish status change event
        PortingEvent.EventType eventType = mapStatusToEvent(newStatus);
        eventPublisher.publish(PortingEvent.builder()
                .eventType(eventType)
                .payload(result)
                .requestId(portRequestId)
                .timestamp(now)
                .sourceService("porting-service")
                .build());

        log.info("Porting request {} updated to status: {}", portRequestId, newStatus);
        return result;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @CacheEvict(value = "portingRequests", key = "#portRequestId")
    public PortingRequestDto cancel(String portRequestId) {
        return updateStatus(portRequestId, PortingStatus.CANCELLED, null, null);
    }

    // ── ClearPort integration (circuit-breaker protected) ────────────────────

    @CircuitBreaker(name = "clearport", fallbackMethod = "clearPortFallback")
    @Retry(name = "clearport")
    private void submitToClearPort(PortingRequest request) {
        try {
            String clearPortRef = clearPortService.submitPortRequest(request);
            request.setClearPortRef(clearPortRef);
            request.setStatus(PortingStatus.SUBMITTED);
            request.setUpdatedAt(Instant.now());
            repository.update(request);
            log.info("Submitted to ClearPort OMS, ref: {}", clearPortRef);
        } catch (Exception e) {
            log.error("ClearPort submission failed for {}: {}", request.getPortRequestId(), e.getMessage());
            // Keep as PENDING — will retry via scheduled job
        }
    }

    private void clearPortFallback(PortingRequest request, Exception e) {
        log.warn("ClearPort circuit breaker open — request {} queued for retry",
                request.getPortRequestId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateStatusTransition(PortingStatus current, PortingStatus next) {
        boolean invalid = switch (current) {
            case COMPLETED, FAILED, CANCELLED -> true;
            case PENDING    -> next == PortingStatus.PENDING;
            case SUBMITTED  -> next == PortingStatus.PENDING;
            case IN_PROGRESS -> next == PortingStatus.PENDING || next == PortingStatus.SUBMITTED;
            case FALLOUT    -> next == PortingStatus.PENDING;
        };
        if (invalid) {
            throw new PortingValidationException("INVALID_TRANSITION",
                    "Cannot transition from " + current + " to " + next);
        }
    }

    private PortingEvent.EventType mapStatusToEvent(PortingStatus status) {
        return switch (status) {
            case SUBMITTED   -> PortingEvent.EventType.PORT_STATUS_SUBMITTED;
            case IN_PROGRESS -> PortingEvent.EventType.PORT_STATUS_IN_PROGRESS;
            case COMPLETED   -> PortingEvent.EventType.PORT_STATUS_COMPLETED;
            case FAILED      -> PortingEvent.EventType.PORT_STATUS_FAILED;
            case FALLOUT     -> PortingEvent.EventType.PORT_STATUS_FALLOUT;
            case CANCELLED   -> PortingEvent.EventType.PORT_STATUS_CANCELLED;
            default          -> PortingEvent.EventType.PORT_INITIATED;
        };
    }
}
