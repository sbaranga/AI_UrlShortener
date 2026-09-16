package com.example.urlshortener.orchestration.ai;

import com.example.urlshortener.orchestration.model.ModuleId;

public record AiTask(String runId, ModuleId module, int attempt) {}
