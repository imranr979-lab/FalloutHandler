package com.nexatel.ssp.fallout.service;

import com.nexatel.ssp.common.event.PortingEvent;
import com.nexatel.ssp.fallout.dto.CreateFalloutRequest;
import com.nexatel.ssp.fallout.dto.FalloutDto;
import com.nexatel.ssp.fallout.dto.ResolveFalloutRequest;
import com.nexatel.ssp.fallout.entity.FalloutRecord;
import com.nexatel.ssp.fallout.enums.FalloutType;
import com.nexatel.ssp.fallout.exception.FalloutNotFoundException;
import com.nexatel.ssp.fallout.messaging.FalloutEventPublisher;
import com.nexatel.ssp.fallout.repository.FalloutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fallout Management Service
 * ===========================
 * Manages porting fallout lifecycle:
 *   - PIN/PW mismatch
 *   - Account number mismatch
 *   - Zip code mismatch
 *   - NPA/NXX invalid
 *   - Market validation failed
 *   - Duplicate request
 *
 * Based on Fallout Management + SSP Module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FalloutService {

    private final FalloutRepository falloutRepository;
    private final FalloutEventPublisher eventPublisher;

    // Standard messages per fallout type (from architecture: "Standard Message")
    private static final Map<FalloutType, String> STANDARD_MESSAGES = Map.of(
        FalloutType.PIN_PW_MISMATCH,
            "The PIN or password provided does not match our records. Please verify and resubmit.",
        FalloutType.ACCOUNT_NUMBER_MISMATCH,
            "The account number provided does not match. Please check your current carrier statement.",
        FalloutType.ZIP_CODE_MISMATCH,
            "The zip code on file does not match your submission. Please verify your billing address.",
        FalloutType.NPA_NXX_INVALID,
            "The area code and exchange for this number are not valid in our system.",
        FalloutType.MARKET_VALIDATION_FAILED,
            "This number is outside of NexaTel's current service area. " +
            "Please visit our coverage map for more information.",
        FalloutType.DUPLICATE_REQUEST,
            "A porting request already exists for this telephone number.",
        FalloutType.UNKNOWN,
            "An unexpected error occurred. Please contact NexaTel support."
    );

    // ── Create fallout ────────────────────────────────────────────────────────

    public FalloutDto create(CreateFalloutRequest request) {
        log.info("Creating fallout for port request: {} type: {}",
                request.getPortRequestId(), request.getFalloutType());

        FalloutType type = FalloutType.valueOf(request.getFalloutType());
        String falloutId = "FO-" + UUID.randomUUID();
        Instant now = Instant.now();

        FalloutRecord record = FalloutRecord.builder()
                .falloutId(falloutId)
                .portRequestId(request.getPortRequestId())
                .portedTn(request.getPortedTn())
                .falloutType(type)
                .falloutMessage(request.getFalloutMessage())
                .standardMessage(STANDARD_MESSAGES.getOrDefault(type, STANDARD_MESSAGES.get(FalloutType.UNKNOWN)))
                .createdAt(now)
                .updatedAt(now)
                .build();

        falloutRepository.save(record);

        // Publish FALLOUT_CREATED event to SQS → triggers SMS notification
        eventPublisher.publish(PortingEvent.builder()
                .eventType(PortingEvent.EventType.FALLOUT_CREATED)
                .requestId(falloutId)
                .timestamp(now)
                .sourceService("fallout-service")
                .build());

        log.info("Fallout created: {} for port: {}", falloutId, request.getPortRequestId());
        return toDto(record);
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    public FalloutDto getById(String falloutId) {
        return falloutRepository.findById(falloutId)
                .map(this::toDto)
                .orElseThrow(() -> new FalloutNotFoundException(falloutId));
    }

    // ── Get by port request ID ────────────────────────────────────────────────

    public List<FalloutDto> getByPortRequestId(String portRequestId) {
        return falloutRepository.findByPortRequestId(portRequestId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ── Resolve fallout ───────────────────────────────────────────────────────

    public FalloutDto resolve(String falloutId, ResolveFalloutRequest request) {
        log.info("Resolving fallout: {} by: {}", falloutId, request.getResolvedBy());

        FalloutRecord existing = falloutRepository.findById(falloutId)
                .orElseThrow(() -> new FalloutNotFoundException(falloutId));

        if (existing.getResolvedAt() != null) {
            throw new IllegalStateException("Fallout " + falloutId + " is already resolved");
        }

        Instant now = Instant.now();
        existing.setResolution(request.getResolution());
        existing.setResolvedBy(request.getResolvedBy());
        existing.setResolvedAt(now);
        existing.setUpdatedAt(now);

        falloutRepository.update(existing);

        // Publish FALLOUT_RESOLVED → triggers SMS notification
        eventPublisher.publish(PortingEvent.builder()
                .eventType(PortingEvent.EventType.FALLOUT_RESOLVED)
                .requestId(falloutId)
                .timestamp(now)
                .sourceService("fallout-service")
                .build());

        log.info("Fallout {} resolved by {}", falloutId, request.getResolvedBy());
        return toDto(existing);
    }

    // ── Get standard messages ─────────────────────────────────────────────────

    public Map<String, String> getStandardMessages() {
        return STANDARD_MESSAGES.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().name(), Map.Entry::getValue));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private FalloutDto toDto(FalloutRecord record) {
        return FalloutDto.builder()
                .falloutId(record.getFalloutId())
                .portRequestId(record.getPortRequestId())
                .portedTn(record.getPortedTn())
                .falloutType(record.getFalloutType().name())
                .falloutMessage(record.getFalloutMessage())
                .standardMessage(record.getStandardMessage())
                .resolution(record.getResolution())
                .resolvedBy(record.getResolvedBy())
                .resolvedAt(record.getResolvedAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}

