package com.nexatel.ssp.market.service;

import com.nexatel.ssp.market.dto.MarketValidationResult;
import com.nexatel.ssp.market.entity.NpaCoverage;
import com.nexatel.ssp.market.repository.NpaCoverageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Market Validation Service
 * ==========================
 * NPA/NXX market coverage validation.
 * Determines if a ported TN is IN_MARKET or OUT_OF_MARKET.
 *
 * Based on: ESR SSP Module → Market Validation / NPA/NXX
 * from the architecture diagram.
 *
 * NPA = Numbering Plan Area (area code, 3 digits)
 * NXX = Central Office Code (exchange, 3 digits)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketValidationService {

    private final NpaCoverageRepository npaCoverageRepository;

    /**
     * Validate a ported TN against market coverage data.
     * Result is cached for 15 minutes (NPA/NXX data changes rarely).
     *
     * @param portedTn 10-digit telephone number
     * @return validation result with market code and in/out of market status
     */
    @Cacheable(value = "marketValidation", key = "#portedTn.substring(0,6)")
    public MarketValidationResult validate(String portedTn) {
        if (portedTn == null || !portedTn.matches("\\d{10}")) {
            throw new IllegalArgumentException("TN must be exactly 10 digits");
        }

        String npaNxx = portedTn.substring(0, 6);
        String npa    = portedTn.substring(0, 3);
        String nxx    = portedTn.substring(3, 6);

        log.info("Validating NPA/NXX: {} (NPA={} NXX={}) for TN: {}", npaNxx, npa, nxx, portedTn);

        return npaCoverageRepository.findByNpaNxx(npaNxx)
                .map(coverage -> MarketValidationResult.builder()
                        .portedTn(portedTn)
                        .npaNxx(npaNxx)
                        .npa(npa)
                        .nxx(nxx)
                        .marketCode(coverage.getMarketCode())
                        .isInMarket(coverage.isInMarket())
                        .region(coverage.getRegion())
                        .validatedAt(Instant.now())
                        .build())
                .orElse(MarketValidationResult.builder()
                        .portedTn(portedTn)
                        .npaNxx(npaNxx)
                        .npa(npa)
                        .nxx(nxx)
                        .marketCode("UNKNOWN")
                        .isInMarket(false)
                        .region("UNKNOWN")
                        .validatedAt(Instant.now())
                        .build());
    }

    /**
     * Get all market coverage entries.
     * Used by SSP admin panel to display coverage map.
     */
    public List<NpaCoverage> getAllCoverage() {
        return npaCoverageRepository.findAll();
    }

    /**
     * Get coverage for a specific market code.
     */
    public List<NpaCoverage> getCoverageByMarket(String marketCode) {
        return npaCoverageRepository.findByMarketCode(marketCode);
    }
}
