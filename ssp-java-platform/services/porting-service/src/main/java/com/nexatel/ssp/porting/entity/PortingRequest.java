package com.nexatel.ssp.porting.entity;

import com.nexatel.ssp.common.enums.PortingStatus;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.time.Instant;

/**
 * DynamoDB entity for a porting request.
 * Mapped via AWS SDK v2 Enhanced DynamoDB client.
 */
@DynamoDbBean
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PortingRequest {

    private String portRequestId;
    private String portedTn;
    private String accountHolderTn;
    private String customerId;
    private PortingStatus status;
    private String marketCode;
    private Instant submittedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private String falloutReason;
    private String orderId;

    /** ClearPort OMS reference number */
    private String clearPortRef;

    @DynamoDbPartitionKey
    public String getPortRequestId() { return portRequestId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "portedTn-index")
    public String getPortedTn() { return portedTn; }

    @DynamoDbSecondaryPartitionKey(indexNames = "customerId-index")
    public String getCustomerId() { return customerId; }
}
