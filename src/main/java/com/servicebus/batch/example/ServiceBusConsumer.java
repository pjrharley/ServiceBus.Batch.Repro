package com.servicebus.batch.example;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverAsyncClient;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class ServiceBusConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusConsumer.class);

    private static final int COMPLETION_CONCURRENCY = 10;
    private final String connectionString;
    private final String queueName;
    private final EventHandler eventHandler;
    private Disposable subscription;
    private ServiceBusReceiverAsyncClient consumer;
    private ServiceBusSenderAsyncClient publisher;
    private final int batchSize;
    private final int prefetch;
    private final int batchConcurrency;
    private final Duration batchTimeout;
    private final Duration maxAutoLockRenewDuration;


    public ServiceBusConsumer(
            @Value("${example.servicebus.connection-string}") String connectionString,
            @Value("${example.servicebus.queue}") String queueName,
            @Value("${example.servicebus.batch-size}") int batchSize,
            @Value("${example.servicebus.prefetch}") int prefetch,
            @Value("${example.servicebus.batch-concurrency}") int batchConcurrency,
            @Value("${example.servicebus.batch-timeout}") Duration batchTimeout,
            @Value("${example.servicebus.max-auto-lock-renew-duration}") Duration maxAutoLockRenewDuration,
            EventHandler eventHandler) {
        this.connectionString = connectionString;
        this.queueName = queueName;
        this.batchSize = batchSize;
        this.prefetch = prefetch;
        this.batchConcurrency = batchConcurrency;
        this.batchTimeout = batchTimeout;
        this.eventHandler = eventHandler;
        this.maxAutoLockRenewDuration = maxAutoLockRenewDuration;
    }

    @PostConstruct
    public void startConsumer() {
        consumer = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .receiver()
                .queueName(queueName)
                .disableAutoComplete()
                .maxAutoLockRenewDuration(maxAutoLockRenewDuration)
                .prefetchCount(prefetch)
                .buildAsyncClient();

        publisher = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .queueName(queueName)
                .buildAsyncClient();

        subscription = consumer
                .receiveMessages()
                .bufferTimeout(batchSize, batchTimeout)
                .flatMap(this::processMessageBatch, batchConcurrency, batchConcurrency)
                .subscribe(state -> {
                    LOGGER.atInfo()
                            .addKeyValue("name", "subscription.on.next")
                            .addKeyValue("state", state)
                            .log();
                }, err -> {
                        LOGGER.atError()
                                .addKeyValue("name", "servicebus.consumer.error")
                                .setCause(err)
                                .log();
                });
    }

    private Mono<State> processMessageBatch(List<ServiceBusReceivedMessage> messages) {
        LOGGER.atInfo()
                .addKeyValue("name", "message.processing.batch.received")
                .addKeyValue("batchSize", messages.size())
                .addKeyValue("messageIds", messages.stream().map(ServiceBusReceivedMessage::getMessageId).toList())
                .addKeyValue("lockTokens", messages.stream().map(m -> m.getLockToken()).toList())
                .log("batch received");
        return eventHandler.accept(messages)
                .then(Mono.just(State.HANDLING_SUCCEEDED))
                .doOnNext(state1 ->
                        LOGGER.atInfo()
                                .addKeyValue("name", "message.processing.success")
                                .addKeyValue("batchSize", messages.size())
                                .addKeyValue("state", state1)
                                .log("message processed")
                )
                .doOnError(error1 ->
                        LOGGER.atWarn()
                                .addKeyValue("name", "message.processing.failed")
                                .addKeyValue("message", error1.getMessage())
                                .log("error processing message", error1)
                )
                .onErrorResume(error -> {
                    if (error instanceof NonRetryableException) {
                        return Flux.fromIterable(messages)
                                .flatMap(message -> {
                                    DeadLetterOptions options = deadLetterOptionsWithReason(error);
                                    LOGGER.atError()
                                            .addKeyValue("lockToken", message.getLockToken())
                                            .addKeyValue("messageId", message.getMessageId())
                                            .addKeyValue("name", "deadLetter.message")
                                            .addKeyValue("reason", options.getDeadLetterReason())
                                            .addKeyValue("errorDesc", options.getDeadLetterErrorDescription())
                                            .log();
                                    return consumer.deadLetter(message, options);
                                }, COMPLETION_CONCURRENCY, COMPLETION_CONCURRENCY)
                                .then(Mono.just(State.MESSAGE_DEAD_LETTERED));
                    } else {
                        return Flux.fromIterable(messages)
                                .flatMap(message -> consumer.abandon(message), COMPLETION_CONCURRENCY, COMPLETION_CONCURRENCY)
                                .then(Mono.just(State.MESSAGE_ABANDONED));
                    }
                })
                .doOnError(errorHandlingError -> LOGGER.atError()
                        .addKeyValue("name", "servicebus.error.handling.failed")
                        .setCause(errorHandlingError)
                        .log())
                .onErrorResume(err -> Mono.just(State.MESSAGE_ERROR_HANDLING_FAILED))
                .flatMap(state -> {
                    if (state == State.HANDLING_SUCCEEDED) {
                        return Flux.fromIterable(messages)
                                .flatMap(m -> {
                                    LOGGER.atInfo()
                                            .addKeyValue("name", "completing.message")
                                            .addKeyValue("lockToken", m.getLockToken())
                                            .addKeyValue("messageId", m.getMessageId())
                                            .log();
                                    return consumer.complete(m)
                                            .doOnSuccess(x -> LOGGER.atInfo()
                                                    .addKeyValue("name", "completed.message")
                                                    .addKeyValue("lockToken", m.getLockToken())
                                                    .addKeyValue("messageId", m.getMessageId())
                                                    .log()
                                            );
                                }, COMPLETION_CONCURRENCY, COMPLETION_CONCURRENCY)
                                .then(Mono.just(State.MESSAGE_COMPLETED))
                                .doOnError(errorCompleting -> LOGGER.atError()
                                        .addKeyValue("name", "servicebus.error.completing.failed")
                                        .setCause(errorCompleting)
                                        .log())
                                .onErrorResume(err -> Mono.just(State.MESSAGE_COMPLETION_FAILED));
                    } else {
                        return Mono.just(state);
                    }
                })
                .doOnNext(state -> LOGGER.atInfo()
                        .addKeyValue("state", state)
                        .addKeyValue("name", "pipeline.completed")
                        .log());
    }

    private DeadLetterOptions deadLetterOptionsWithReason(Throwable error) {
        DeadLetterOptions options = new DeadLetterOptions();
        options.setDeadLetterReason("Failed to process message");
        options.setDeadLetterErrorDescription(error.getMessage());
        return options;
    }

    @PreDestroy
    public void destroyConsumer() {
        subscription.dispose();
        consumer.close();
        publisher.close();
    }

    private enum State {
        HANDLING_SUCCEEDED,
        MESSAGE_COMPLETED,
        MESSAGE_ABANDONED,
        MESSAGE_DEAD_LETTERED,
        MESSAGE_RESCHEDULED,
        MESSAGE_COMPLETION_FAILED,
        MESSAGE_ERROR_HANDLING_FAILED
    }
}