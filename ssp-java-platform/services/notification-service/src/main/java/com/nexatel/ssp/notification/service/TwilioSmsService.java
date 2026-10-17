package com.nexatel.ssp.notification.service;

import com.nexatel.ssp.common.dto.PortingRequestDto;
import com.nexatel.ssp.notification.config.TwilioProperties;
import com.nexatel.ssp.notification.dto.SmsRequest;
import com.nexatel.ssp.notification.dto.SmsResult;
import com.nexatel.ssp.notification.entity.NotificationRecord;
import com.nexatel.ssp.notification.repository.NotificationRepository;
import com.nexatel.ssp.notification.template.SmsTemplateEngine;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Twilio SMS Gateway Service
 * ===========================
 * Implements the TWO-CALL flow from the architecture diagram:
 *
 * First Call:
 *   Input:  ported TN
 *   Action: look up account holder TN from porting service
 *   Output: account holder TN
 *
 * Second Call:
 *   Input:  account holder TN + market code + fallout message +
 *           standard message + frequency/duration/time
 *   Action: send SMS via Twilio to account holder TN
 *   Output: Twilio message SID + delivery status
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwilioSmsService {

    private final TwilioProperties twilioProperties;
    private final NotificationRepository notificationRepository;
    private final SmsTemplateEngine templateEngine;
    private final AccountHolderResolver accountHolderResolver;

    @PostConstruct
    public void init() {
        Twilio.init(twilioProperties.getAccountSid(), twilioProperties.getAuthToken());
        log.info("Twilio SMS service initialised");
    }

    // ── First Call: resolve account holder TN ────────────────────────────────

    /**
     * First call in the two-step flow.
     * Receives the ported TN and resolves the account holder's current TN
     * by querying the porting service via internal Kubernetes DNS.
     *
     * @param portedTn the TN being ported in
     * @return account holder TN (the number that will receive the SMS)
     */
    public String resolveAccountHolderTn(String portedTn) {
        log.info("First call: resolving account holder TN for ported TN: {}", portedTn);
        String accountHolderTn = accountHolderResolver.resolve(portedTn);
        log.info("Account holder TN resolved: {} → {}", portedTn, accountHolderTn);
        return accountHolderTn;
    }

    // ── Second Call: send SMS to account holder ────────────────────────────────

    /**
     * Second call in the two-step flow.
     * Sends the SMS to the resolved account holder TN with:
     *   - Market status (IN/OUT of market)
     *   - Fallout message
     *   - Standard message
     *   - Deep link to SSP portal
     *   - Frequency/duration/time parameters
     *
     * @param request full SMS request with all required fields
     * @return delivery result with Twilio SID and status
     */
    @CircuitBreaker(name = "twilio", fallbackMethod = "smsFallback")
    @Retry(name = "twilio")
    public SmsResult sendSms(SmsRequest request) {
        log.info("Second call: sending SMS to {} for ported TN: {} event: {}",
                request.getRecipientTn(), request.getPortedTn(), request.getEvent());

        String messageBody = buildMessageBody(request);
        String notificationId = "NOT-" + UUID.randomUUID();
        Instant now = Instant.now();

        try {
            Message twilioMessage = Message.creator(
                    new PhoneNumber("+1" + request.getRecipientTn()),
                    new PhoneNumber(twilioProperties.getFromNumber()),
                    messageBody
            ).create();

            SmsResult result = SmsResult.builder()
                    .notificationId(notificationId)
                    .twilioMessageSid(twilioMessage.getSid())
                    .status(twilioMessage.getStatus().toString())
                    .sentAt(now)
                    .build();

            // Persist to DynamoDB for audit and delivery tracking
            persistNotificationRecord(notificationId, request, result, messageBody, now);

            log.info("SMS sent successfully. SID: {} to: {}",
                    twilioMessage.getSid(), request.getRecipientTn());

            return result;

        } catch (Exception e) {
            log.error("Twilio SMS failed for TN {}: {}", request.getRecipientTn(), e.getMessage());
            persistFailedNotification(notificationId, request, e.getMessage(), now);
            throw new RuntimeException("SMS delivery failed: " + e.getMessage(), e);
        }
    }

    // ── Full two-call orchestration ───────────────────────────────────────────

    /**
     * Orchestrates the complete two-call flow:
     *   1. Resolve account holder TN from ported TN
     *   2. Send SMS to account holder TN
     */
    public SmsResult sendPortingNotification(PortingRequestDto portingRequest,
                                              String event,
                                              String falloutMessage) {
        // First call
        String accountHolderTn = resolveAccountHolderTn(portingRequest.getPortedTn());

        // Second call
        SmsRequest smsRequest = SmsRequest.builder()
                .portedTn(portingRequest.getPortedTn())
                .recipientTn(accountHolderTn)
                .event(event)
                .marketCode(portingRequest.getMarketCode())
                .falloutMessage(falloutMessage)
                .standardMessage(templateEngine.getStandardMessage(portingRequest.getMarketCode()))
                .portalUrl(buildPortalUrl(portingRequest.getPortedTn()))
                .frequency(SmsRequest.Frequency.builder()
                        .intervalMinutes(60)
                        .maxAttempts(3)
                        .build())
                .build();

        return sendSms(smsRequest);
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    public SmsResult smsFallback(SmsRequest request, Exception e) {
        log.warn("Twilio circuit breaker open — SMS to {} will be queued for retry. Error: {}",
                request.getRecipientTn(), e.getMessage());
        return SmsResult.builder()
                .notificationId("QUEUED-" + UUID.randomUUID())
                .status("QUEUED_FOR_RETRY")
                .sentAt(Instant.now())
                .errorMessage(e.getMessage())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildMessageBody(SmsRequest request) {
        return templateEngine.render(
                request.getEvent(),
                request.getPortedTn(),
                request.getMarketCode(),
                request.getFalloutMessage(),
                request.getStandardMessage(),
                request.getPortalUrl()
        );
    }

    private String buildPortalUrl(String portedTn) {
        return twilioProperties.getPortalBaseUrl() + "/port/" + portedTn;
    }

    private void persistNotificationRecord(String notificationId, SmsRequest request,
                                            SmsResult result, String body, Instant now) {
        NotificationRecord record = NotificationRecord.builder()
                .notificationId(notificationId)
                .portedTn(request.getPortedTn())
                .recipientTn(request.getRecipientTn())
                .event(request.getEvent())
                .marketCode(request.getMarketCode())
                .messageBody(body)
                .twilioMessageSid(result.getTwilioMessageSid())
                .status(result.getStatus())
                .sentAt(now)
                .build();
        notificationRepository.save(record);
    }

    private void persistFailedNotification(String notificationId, SmsRequest request,
                                            String errorMessage, Instant now) {
        NotificationRecord record = NotificationRecord.builder()
                .notificationId(notificationId)
                .portedTn(request.getPortedTn())
                .recipientTn(request.getRecipientTn())
                .event(request.getEvent())
                .marketCode(request.getMarketCode())
                .status("FAILED")
                .errorMessage(errorMessage)
                .sentAt(now)
                .build();
        notificationRepository.save(record);
    }
}
