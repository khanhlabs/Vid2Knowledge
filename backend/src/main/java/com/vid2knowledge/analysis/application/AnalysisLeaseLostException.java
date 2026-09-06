package com.vid2knowledge.analysis.application;

public class AnalysisLeaseLostException extends RuntimeException {
    public AnalysisLeaseLostException() {
        super("Analysis job is no longer owned by this worker");
    }
}
