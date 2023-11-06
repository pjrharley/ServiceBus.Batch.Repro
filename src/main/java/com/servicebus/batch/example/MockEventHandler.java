package com.servicebus.batch.example;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;

import static org.apache.commons.lang3.RandomUtils.nextInt;

@Component
public class MockEventHandler implements EventHandler {
    @Override
    public Mono<Void> accept(Collection<ServiceBusReceivedMessage> events) {
        return Flux.fromIterable(events)
                .map(x -> x.getBody())
                .then(Mono.defer(() -> Mono.delay(Duration.ofMillis(nextInt(100, 2000)))))
                .then();
    }
}