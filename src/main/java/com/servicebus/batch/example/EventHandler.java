package com.servicebus.batch.example;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface EventHandler {
    Mono<Void> accept(Collection<ServiceBusReceivedMessage> events);
}