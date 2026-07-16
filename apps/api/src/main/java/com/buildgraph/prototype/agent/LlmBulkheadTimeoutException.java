package com.buildgraph.prototype.agent;

/** LLM 격벽에서 호출이 제한 시간을 초과했음을 나타낸다. 호출부는 결정론 폴백으로 강등한다. */
public class LlmBulkheadTimeoutException extends RuntimeException {
    public LlmBulkheadTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
