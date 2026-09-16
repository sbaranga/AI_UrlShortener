package com.example.urlshortener.orchestration.dto;

import com.example.urlshortener.orchestration.model.ModuleId;

/**
 * The human gatepoint.
 *
 * @param pending true while the run is blocked waiting for a verification key
 */
public record ApprovalView(ModuleId module, String moduleName, boolean pending) {
}
