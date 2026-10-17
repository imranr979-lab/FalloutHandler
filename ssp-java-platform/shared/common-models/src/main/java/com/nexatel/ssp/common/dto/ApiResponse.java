package com.nexatel.ssp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;

/**
 * Standardised API response envelope — used by all microservices.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private ApiError error;
    private ResponseMeta meta;

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .meta(ResponseMeta.builder()
                        .requestId(requestId)
                        .timestamp(Instant.now())
                        .build())
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, String requestId) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(ApiError.builder().code(code).message(message).build())
                .meta(ResponseMeta.builder()
                        .requestId(requestId)
                        .timestamp(Instant.now())
                        .build())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiError {
        private String code;
        private String message;
        private Object details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMeta {
        private String requestId;
        private Instant timestamp;
        private Pagination pagination;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pagination {
        private int page;
        private int limit;
        private long total;
        private int totalPages;
    }
}
