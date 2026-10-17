package com.nexatel.ssp.notification.template;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SMS message template engine.
 * Generates context-aware SMS bodies for each porting event type.
 *
 * Mirrors the "Standard Message" and "Fallout Message" fields
 * from the ESR SSP Module in the architecture diagram.
 */
@Component
public class SmsTemplateEngine {

    private static final Map<String, String> STANDARD_MESSAGES = Map.of(
        "OUT_OF_MARKET",
        "NexaTel: Your number {portedTn} is currently outside our service area ({marketCode}). " +
        "For assistance visit: {portalUrl}",

        "IN_MARKET",
        "NexaTel: Your number {portedTn} is in our service area. Porting will proceed. " +
        "Track at: {portalUrl}"
    );

    /**
     * Render an SMS message for a given event type.
     */
    public String render(String event, String portedTn, String marketCode,
                         String falloutMessage, String standardMessage, String portalUrl) {
        return switch (event) {
            case "PORT_IN_INITIATED" ->
                "NexaTel: Porting request for " + portedTn + " has been initiated. " +
                "Track at: " + portalUrl;

            case "PORT_IN_COMPLETED" ->
                "NexaTel: Your number " + portedTn + " has been ported successfully! " +
                "Welcome to NexaTel. Account details: " + portalUrl;

            case "PORT_IN_FAILED" ->
                "NexaTel: Porting for " + portedTn + " could not complete. " +
                "Reason: " + (falloutMessage != null ? falloutMessage : "Please contact support") +
                " Help: " + portalUrl;

            case "FALLOUT_DETECTED" ->
                "NexaTel Alert: Action required for porting " + portedTn + ". " +
                (falloutMessage != null ? falloutMessage + " " : "") +
                "Resolve at: " + portalUrl;

            case "FALLOUT_RESOLVED" ->
                "NexaTel: Your porting request for " + portedTn + " is back on track. " +
                "Track at: " + portalUrl;

            case "OUT_OF_MARKET" ->
                (standardMessage != null ? standardMessage : STANDARD_MESSAGES.get("OUT_OF_MARKET"))
                    .replace("{portedTn}", portedTn)
                    .replace("{marketCode}", marketCode != null ? marketCode : "UNKNOWN")
                    .replace("{portalUrl}", portalUrl);

            case "IN_MARKET" ->
                STANDARD_MESSAGES.get("IN_MARKET")
                    .replace("{portedTn}", portedTn)
                    .replace("{portalUrl}", portalUrl);

            default ->
                "NexaTel: Update for your porting request " + portedTn + ". " +
                "Details: " + portalUrl;
        };
    }

    public String getStandardMessage(String marketCode) {
        return "Your porting request is being processed in market area " + marketCode + ".";
    }
}
