package com.vid2knowledge.analysis.application;

public class SourceRightsRequiredException extends RuntimeException {

    public SourceRightsRequiredException() {
        super("The source does not exist in this organization or has no active rights attestation");
    }
}
