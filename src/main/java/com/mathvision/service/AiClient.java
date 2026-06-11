package com.mathvision.service;

import com.mathvision.model.AiError;
import com.mathvision.model.AiRequest;
import com.mathvision.model.AiResponse;
import com.mathvision.util.ConcurrencyUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Abstraction for LLM chat APIs.
 */
public interface AiClient {

    CompletableFuture<AiResponse> chatAsync(AiRequest request);

    default AiResponse chat(AiRequest request) {
        try {
            return chatAsync(request).join();
        } catch (CompletionException e) {
            Throwable cause = ConcurrencyUtils.unwrapCompletionException(e);
            return AiResponse.failure(AiError.fromException(cause));
        }
    }
}
