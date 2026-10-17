package com.nexatel.ssp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexatel.ssp.common.enums.PortingStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.Instant;

/**
 * Porting Request DTO — shared across all microservices.
 *
 * Company names are fictitious:
 *   NexaTel   = wireless carrier
 *   ClearPort = OMS clearinghouse (replaces Neustar)
 *   OrderFlow = BSS/OSS system (replaces Amdocs)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortingRequestDto {

    private String portRequestId;

    @NotBlank(message = "Ported TN is required")
    @Pattern(regexp = "^\\d{10}$", message = "TN must be exactly 10 digits")
    private String portedTn;

    private String accountHolderTn;

    @NotBlank(message = "Customer ID is required")
    private String customerId;

    private PortingStatus status;

    @NotBlank(message = "Market code is required")
    private String marketCode;

    private Instant submittedAt;
    private Instant updatedAt;
    private Instant completedAt;

    private String falloutReason;

    @NotBlank(message = "Order ID is required")
    private String orderId;

    /** Reference ID from ClearPort OMS clearinghouse */
    private String clearPortRef;
}
