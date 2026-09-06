package com.vid2knowledge.billing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PayOsSignatureTest {

    @Test
    void signsCheckoutUsingPayOsAlphabeticalContract() {
        assertThat(PayOsSignature.signCheckout(
                790_000,
                "https://app.example/cancel",
                "V2K 100000001",
                100000001,
                "https://app.example/success",
                "secret"
        )).isEqualTo("eb3fb84abf315b9bef257fb1617d6a24449f72ce0eaba80314fdc7ab1345071f");
    }

    @Test
    void verifiesWebhookIndependentOfJsonFieldOrderAndRejectsTampering() {
        var mapper = new ObjectMapper();
        var first = mapper.readTree("{\"reference\":\"ref-1\",\"amount\":790000,\"orderCode\":100000001}");
        var reordered = mapper.readTree("{\"orderCode\":100000001,\"amount\":790000,\"reference\":\"ref-1\"}");
        String signature = PayOsSignature.signWebhook(first, "secret");

        assertThat(PayOsSignature.verifyWebhook(reordered, signature, "secret")).isTrue();
        assertThat(PayOsSignature.verifyWebhook(
                mapper.readTree("{\"orderCode\":100000001,\"amount\":1,\"reference\":\"ref-1\"}"),
                signature,
                "secret"
        )).isFalse();
    }
}
