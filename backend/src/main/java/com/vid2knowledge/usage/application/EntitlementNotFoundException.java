package com.vid2knowledge.usage.application;

public class EntitlementNotFoundException extends RuntimeException {

    public EntitlementNotFoundException() {
        super("No active entitlement exists for this usage metric");
    }
}
