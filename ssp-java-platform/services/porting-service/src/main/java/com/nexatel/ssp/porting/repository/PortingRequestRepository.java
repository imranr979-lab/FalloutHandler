package com.nexatel.ssp.porting.repository;

import com.nexatel.ssp.porting.entity.PortingRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.enhanced.dynamodb.model.*;

import java.util.List;
import java.util.Optional;

/**
 * DynamoDB repository for porting requests.
 * Uses AWS SDK v2 Enhanced DynamoDB client with table schema mapping.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PortingRequestRepository {

    private final DynamoDbEnhancedClient enhancedClient;

    @Value("${aws.dynamodb.porting-table}")
    private String tableName;

    private DynamoDbTable<PortingRequest> getTable() {
        return enhancedClient.table(tableName,
                TableSchema.fromBean(PortingRequest.class));
    }

    public PortingRequest save(PortingRequest request) {
        log.debug("Saving porting request: {}", request.getPortRequestId());
        getTable().putItem(request);
        return request;
    }

    public Optional<PortingRequest> findById(String portRequestId) {
        Key key = Key.builder().partitionValue(portRequestId).build();
        return Optional.ofNullable(getTable().getItem(key));
    }

    public Optional<PortingRequest> findByPortedTn(String portedTn) {
        DynamoDbIndex<PortingRequest> index = getTable().index("portedTn-index");

        QueryConditional queryConditional = QueryConditional
                .keyEqualTo(Key.builder().partitionValue(portedTn).build());

        return index.query(r -> r.queryConditional(queryConditional)
                        .limit(1)
                        .scanIndexForward(false))
                .stream()
                .flatMap(page -> page.items().stream())
                .findFirst();
    }

    public List<PortingRequest> findByCustomerId(String customerId) {
        DynamoDbIndex<PortingRequest> index = getTable().index("customerId-index");

        QueryConditional queryConditional = QueryConditional
                .keyEqualTo(Key.builder().partitionValue(customerId).build());

        return index.query(queryConditional)
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    public PortingRequest update(PortingRequest request) {
        log.debug("Updating porting request: {}", request.getPortRequestId());
        getTable().updateItem(request);
        return request;
    }

    public void deleteById(String portRequestId) {
        Key key = Key.builder().partitionValue(portRequestId).build();
        getTable().deleteItem(key);
    }
}
