package com.servicebus.batch.example;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverAsyncClient;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * A facade receiver client that uses {@link ServiceBusReceiverAsyncClient} underneath, stream messages from it,
 * but additionally listen for terminal error and create a new {@link ServiceBusReceiverAsyncClient}
 * to continue the message delivery.
 */
class ServiceBusIndefiniteRetryReceiverAsyncClient implements Disposable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusIndefiniteRetryReceiverAsyncClient.class);    // On rare cases when Retry exhausts or a non-retryable error occurs do a fixed back-off for 4 sec.
    private static final Duration RETRY_WAIT_TIME = Duration.ofSeconds(4);
    private final AtomicReference<ServiceBusReceiverAsyncClient> currentLowLevelClient = new AtomicReference<>();
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicBoolean isInitial = new AtomicBoolean(true);
    private final Supplier<ServiceBusReceiverAsyncClient> createLowLevelClient;

    /**
     * Creates an instance of ServiceBusIndefiniteRetryReceiverAsyncClient.
     *
     */
    ServiceBusIndefiniteRetryReceiverAsyncClient(Supplier<ServiceBusReceiverAsyncClient> createLowLevelClient) {
        this.createLowLevelClient = createLowLevelClient;
        this.currentLowLevelClient.set(createLowLevelClient.get());
    }

    /**
     * Receive messages from the Service Bus queue.
     *
     * @return a {@link Flux} that streams messages from the Service Bus queue, transparently retrying if
     * the underlying {@link ServiceBusReceiverAsyncClient} terminate with error.
     */
    Flux<ServiceBusReceivedMessage> receiveMessages() {
        return Flux.using(
                        () -> {
                            if (isClosed.get()) {
                                throw new IllegalStateException("Cannot perform receive on the closed client.");
                            }
                            if (!isInitial.getAndSet(false)) {
                                LOGGER.info("Creating a new LowLevelClient");
                                currentLowLevelClient.set(createLowLevelClient.get());
                            }
                            return currentLowLevelClient.get();
                        },
                        client ->  {
                            return client.receiveMessages();
                        },
                        client -> {
                            LOGGER.info("Disposing current LowLevelClient");
                            client.close();
                        })
                .retryWhen(
                        Retry.fixedDelay(Long.MAX_VALUE, RETRY_WAIT_TIME)
                                .filter(throwable -> {
                                    if (isClosed.get()) {
                                        return false;
                                    }
                                    LOGGER.warn("Current LowLevelClient's retry exhausted or a non-retryable error occurred.",
                                            throwable);
                                    return true;
                                }));
    }

    /**
     * Completes a {@link ServiceBusReceivedMessage message}. This will delete the message from the service.
     *
     * @param message the {@link ServiceBusReceivedMessage} to perform this operation.
     * @return a {@link Mono} that finishes when the message is completed on Service Bus.
     */
    Mono<Void> complete(ServiceBusReceivedMessage message) {
        final ServiceBusReceiverAsyncClient lowLevelClient = currentLowLevelClient.get();
        return lowLevelClient.complete(message);
    }

    Mono<Void> deadLetter(ServiceBusReceivedMessage message, DeadLetterOptions options) {
        final ServiceBusReceiverAsyncClient lowLevelClient = currentLowLevelClient.get();
        return lowLevelClient.deadLetter(message, options);
    }

    /**
     * Abandons a {@link ServiceBusReceivedMessage message}. This will make the message available again for processing.
     * Abandoning a message will increase the delivery count on the message.
     *
     * @param message the {@link ServiceBusReceivedMessage} to perform this operation.
     * @return a {@link Mono} that completes when the Service Bus abandon operation completes.
     */
    Mono<Void> abandon(ServiceBusReceivedMessage message) {
        final ServiceBusReceiverAsyncClient lowLevelClient = currentLowLevelClient.get();
        return lowLevelClient.abandon(message);
    }

    /**
     * Disposes of the client by closing the underlying {@link ServiceBusReceiverAsyncClient}.
     */
    @Override
    public void dispose() {
        if (!isClosed.getAndSet(true)) {
            this.currentLowLevelClient.get().close();
        }
    }
}