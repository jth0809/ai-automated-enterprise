package com.aienterprise.backend.tracker.evaluate;

public class CostLimitExceededException extends RuntimeException {

    public CostLimitExceededException() {
        super("Daily tracker LLM cost cap has been reached");
    }
}
