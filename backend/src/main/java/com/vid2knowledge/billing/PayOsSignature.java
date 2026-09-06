package com.vid2knowledge.billing;

import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

public final class PayOsSignature {

    private PayOsSignature() {
    }

    public static String signCheckout(
            long amount,
            String cancelUrl,
            String description,
            long orderCode,
            String returnUrl,
            String checksumKey
    ) {
        return hmac(
                "amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description
                        + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl,
                checksumKey
        );
    }

    public static boolean verifyWebhook(JsonNode data, String signature, String checksumKey) {
        if (data == null || !data.isObject() || signature == null) {
            return false;
        }
        String canonical = canonicalWebhookData(data);
        byte[] expected = hmac(canonical, checksumKey).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    public static String signWebhook(JsonNode data, String checksumKey) {
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("Webhook data must be a JSON object");
        }
        return hmac(canonicalWebhookData(data), checksumKey);
    }

    private static String canonicalWebhookData(JsonNode data) {
        return data.properties().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + scalar(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String hmac(String value, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not calculate payOS signature", exception);
        }
    }
}
