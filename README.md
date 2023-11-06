# Servicebus Batch POC

Example application to demonstrate overzealous buffering of messages
by the service bus client.

## Running

Create a service bus namespace and a queue with the default settings. Upload a few thousand random messages to the queue, and then run:

```shell
EXAMPLE_SERVICEBUS_QUEUE=my-queue-name EXAMPLE_SERVICEBUS_CONNECTION_STRING="Endpoint=sb://my-example-namespace.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=sharedAccessKey" ./gradlew bootRun
```


## Observations

With the default settings you should see after a while that the locks have expired when trying to complete 
messages. You can speed this up by reducing the default length of the lock on your queue.

In the logs you will see logs from the Azure SDK that look something like this:

```
{"timestamp":"2023-11-06 12:35:51.472","level":"DEBUG","thread":"boundedElastic-1","logger":"com.azure.messaging.servicebus.implementation.ServiceBusReceiveLinkProcessor","message":"{\"az.sdk.message\":\"Adding credits.\",\"prefetch\":50,\"requested\":2,\"linkCredits\":62,\"expectedTotalCredit\":50,\"queuedMessages\":1,\"creditsToAdd\":0,\"messageQueueSize\":0}","context":"default"}
```

At some point there will be loads of these in a row where they all contain something like `"creditsToAdd": 45` (some number less than 50)
and then the `messageQueueSize` will jump into the thousands.