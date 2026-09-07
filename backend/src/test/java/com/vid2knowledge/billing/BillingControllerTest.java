package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingControllerTest {
    @Test
    void invoiceCsvIsPrivateUtf8AndNeutralizesSpreadsheetFormulas() {
        BillingService billing = mock(BillingService.class);
        TenantAccessService access = mock(TenantAccessService.class);
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken("subject", "token");
        when(access.require(eq(organizationId), eq(authentication), any(CurrentActor.Role[].class)))
                .thenReturn(new CurrentActor(userId, organizationId, CurrentActor.Role.OWNER));
        when(billing.invoices(organizationId)).thenReturn(List.of(new BillingService.InvoiceView(
                UUID.randomUUID(), "=HYPERLINK(\"https://attacker\")", "PAID", "VND", "SUBSCRIPTION",
                790_000, 79_000, 711_000, 711_000, "REF_10", "BUSINESS", "=DANGEROUS NAME",
                "0312345678", "+DANGEROUS ADDRESS", "billing@example.vn", "VN", 1L, true,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z")
        )));

        var response = new BillingController(billing, access).exportInvoices(organizationId, authentication);
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        assertThat(csv).startsWith("\uFEFFinvoice_number")
                .contains("\"'=HYPERLINK(\"\"https://attacker\"\")\"")
                .contains("\"'=DANGEROUS NAME\"")
                .contains("\"'+DANGEROUS ADDRESS\"");
    }
}
