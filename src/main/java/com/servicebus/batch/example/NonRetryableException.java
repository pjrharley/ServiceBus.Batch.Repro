package com.servicebus.batch.example;

public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }
}