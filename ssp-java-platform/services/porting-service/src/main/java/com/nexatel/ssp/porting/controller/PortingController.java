package com.nexatel.ssp.porting.controller;

import com.nexatel.ssp.common.dto.ApiResponse;
import com.nexatel.ssp.common.dto.PortingRequestDto;
import com.nexatel.ssp.common.enums.PortingStatus;
import com.nexatel.ssp.porting.controller.request.UpdateStatusRequest;
import com.nexatel.ssp.porting.service.PortingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for number porting lifecycle.
 * All endpoints require JWT authentication via API Gateway.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/port")
@RequiredArgsConstructor
@Tag(name = "Porting", description = "Number porting lifecycle management")
public class PortingController {

    private final PortingService portingService;

    // ── POST /api/v1/port — Initiate ─────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('AGENT','SUPERVISOR','ADMIN','SYSTEM')")
    @Operation(summary = "Initiate a new number porting request")
    public ResponseEntity<ApiResponse<PortingRequestDto>> initiate(
            @Valid @RequestBody PortingRequestDto dto,
            HttpServletRequest request) {

        String requestId = getRequestId(request);
        log.info("Initiating port request for TN: {} [{}]", dto.getPortedTn(), requestId);

        PortingRequestDto result = portingService.initiate(dto);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(result, requestId));
    }

    // ── GET /api/v1/port/{portRequestId} ─────────────────────────────────────

    @GetMapping("/{portRequestId}")
    @PreAuthorize("hasAnyRole('AGENT','SUPERVISOR','ADMIN','SYSTEM')")
    @Operation(summary = "Get a porting request by ID")
    public ResponseEntity<ApiResponse<PortingRequestDto>> getById(
            @PathVariable String portRequestId,
            HttpServletRequest request) {

        PortingRequestDto result = portingService.getById(portRequestId);
        return ResponseEntity.ok(ApiResponse.ok(result, getRequestId(request)));
    }

    // ── GET /api/v1/port/tn/{portedTn} ───────────────────────────────────────

    @GetMapping("/tn/{portedTn}")
    @PreAuthorize("hasAnyRole('AGENT','SUPERVISOR','ADMIN','SYSTEM')")
    @Operation(summary = "Look up a porting request by ported TN")
    public ResponseEntity<ApiResponse<PortingRequestDto>> getByPortedTn(
            @PathVariable String portedTn,
            HttpServletRequest request) {

        PortingRequestDto result = portingService.getByPortedTn(portedTn);
        return ResponseEntity.ok(ApiResponse.ok(result, getRequestId(request)));
    }

    // ── GET /api/v1/port/customer/{customerId} ────────────────────────────────

    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('AGENT','SUPERVISOR','ADMIN')")
    @Operation(summary = "Get all porting requests for a customer")
    public ResponseEntity<ApiResponse<List<PortingRequestDto>>> getByCustomerId(
            @PathVariable String customerId,
            HttpServletRequest request) {

        List<PortingRequestDto> results = portingService.getByCustomerId(customerId);
        return ResponseEntity.ok(ApiResponse.ok(results, getRequestId(request)));
    }

    // ── PATCH /api/v1/port/{portRequestId}/status ─────────────────────────────

    @PatchMapping("/{portRequestId}/status")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN','SYSTEM')")
    @Operation(summary = "Update the status of a porting request")
    public ResponseEntity<ApiResponse<PortingRequestDto>> updateStatus(
            @PathVariable String portRequestId,
            @Valid @RequestBody UpdateStatusRequest updateRequest,
            HttpServletRequest request) {

        String requestId = getRequestId(request);
        log.info("Updating port {} to status: {} [{}]",
                portRequestId, updateRequest.getStatus(), requestId);

        PortingRequestDto result = portingService.updateStatus(
                portRequestId,
                updateRequest.getStatus(),
                updateRequest.getFalloutReason(),
                updateRequest.getClearPortRef()
        );
        return ResponseEntity.ok(ApiResponse.ok(result, requestId));
    }

    // ── DELETE /api/v1/port/{portRequestId} (cancel) ──────────────────────────

    @DeleteMapping("/{portRequestId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
    @Operation(summary = "Cancel a porting request")
    public ResponseEntity<ApiResponse<PortingRequestDto>> cancel(
            @PathVariable String portRequestId,
            HttpServletRequest request) {

        PortingRequestDto result = portingService.cancel(portRequestId);
        return ResponseEntity.ok(ApiResponse.ok(result, getRequestId(request)));
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("UP");
    }

    private String getRequestId(HttpServletRequest request) {
        String id = request.getHeader("X-Request-ID");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }
}
