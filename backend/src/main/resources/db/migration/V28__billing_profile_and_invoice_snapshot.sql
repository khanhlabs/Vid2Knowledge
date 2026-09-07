CREATE TABLE organization_billing_profiles (
    organization_id UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    buyer_type VARCHAR(16) NOT NULL,
    legal_name VARCHAR(240) NOT NULL,
    tax_identifier VARCHAR(32),
    billing_address VARCHAR(500) NOT NULL,
    billing_email VARCHAR(320) NOT NULL,
    country_code CHAR(2) NOT NULL DEFAULT 'VN',
    invoice_requested BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_billing_profile_buyer_type CHECK (buyer_type IN ('BUSINESS', 'INDIVIDUAL')),
    CONSTRAINT ck_billing_profile_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_billing_profile_business_tax CHECK (
        buyer_type <> 'BUSINESS' OR tax_identifier IS NOT NULL
    )
);

ALTER TABLE invoices
    ADD COLUMN buyer_type_snapshot VARCHAR(16),
    ADD COLUMN buyer_legal_name_snapshot VARCHAR(240),
    ADD COLUMN buyer_tax_identifier_snapshot VARCHAR(32),
    ADD COLUMN buyer_address_snapshot VARCHAR(500),
    ADD COLUMN buyer_email_snapshot VARCHAR(320),
    ADD COLUMN buyer_country_code_snapshot CHAR(2),
    ADD COLUMN billing_profile_version BIGINT,
    ADD COLUMN tax_document_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE invoices ADD CONSTRAINT ck_invoice_buyer_snapshot CHECK (
    (buyer_type_snapshot IS NULL AND buyer_legal_name_snapshot IS NULL
        AND buyer_tax_identifier_snapshot IS NULL AND buyer_address_snapshot IS NULL
        AND buyer_email_snapshot IS NULL AND buyer_country_code_snapshot IS NULL
        AND billing_profile_version IS NULL)
    OR
    (buyer_type_snapshot IN ('BUSINESS', 'INDIVIDUAL') AND buyer_legal_name_snapshot IS NOT NULL
        AND buyer_address_snapshot IS NOT NULL AND buyer_email_snapshot IS NOT NULL
        AND buyer_country_code_snapshot IS NOT NULL AND billing_profile_version IS NOT NULL
        AND (buyer_type_snapshot <> 'BUSINESS' OR buyer_tax_identifier_snapshot IS NOT NULL))
);
