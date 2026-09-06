package com.vid2knowledge.analysis.application;

public class InvalidLearningPackageException extends RuntimeException {

    public InvalidLearningPackageException(String message) {
        super(message);
    }

    public InvalidLearningPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
