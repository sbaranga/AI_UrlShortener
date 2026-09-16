package com.example.urlshortener.orchestration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The manual verification key that releases the final gate.
 *
 * @param approver optional name recorded in the ledger; the key itself never is
 */
public record ApprovalRequest(
        @NotBlank(message = "verificationKey is required")
        @Size(max = 128, message = "verificationKey must be at most 128 characters")
        String verificationKey,

        @Size(max = 64, message = "approver must be at most 64 characters")
        String approver) {
}
