package com.buildgraph.prototype.agent;

/** LLM 격벽이 동시 처리 한도를 넘어 호출을 거절했음을 나타낸다(요청 스레드 보호). 호출부는 결정론 폴백으로 강등한다. */
public class LlmBulkheadRejectedException extends RuntimeException {
    public LlmBulkheadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
