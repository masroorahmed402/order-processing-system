package com.example.orders.common.testsupport;

import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.Map;

/** Raw SDK clients for tests to inspect and manipulate queues and tables directly. */
public final class AwsTestClients {

    private static SqsClient sqs;
    private static DynamoDbClient dynamo;

    private AwsTestClients() {
    }

    public static synchronized SqsClient sqs() {
        if (sqs == null) {
            LocalStackContainer ls = TestInfrastructure.localstack();
            sqs = SqsClient.builder()
                    .endpointOverride(ls.getEndpoint())
                    .region(Region.of(ls.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(ls.getAccessKey(), ls.getSecretKey())))
                    .build();
        }
        return sqs;
    }

    public static synchronized DynamoDbClient dynamo() {
        if (dynamo == null) {
            LocalStackContainer ls = TestInfrastructure.localstack();
            dynamo = DynamoDbClient.builder()
                    .endpointOverride(ls.getEndpoint())
                    .region(Region.of(ls.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(ls.getAccessKey(), ls.getSecretKey())))
                    .build();
        }
        return dynamo;
    }

    public static String queueUrl(String queueName) {
        return sqs().getQueueUrl(r -> r.queueName(queueName)).queueUrl();
    }

    public static void send(String queueName, String body) {
        sqs().sendMessage(r -> r.queueUrl(queueUrl(queueName)).messageBody(body));
    }

    /** Visible + in-flight messages, i.e. everything not yet deleted. */
    public static int approximateMessages(String queueName) {
        Map<QueueAttributeName, String> attrs = sqs().getQueueAttributes(r -> r.queueUrl(queueUrl(queueName))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }

    /** Receives (without deleting) up to 10 messages, including their message attributes. */
    public static List<Message> peek(String queueName) {
        return sqs().receiveMessage(r -> r.queueUrl(queueUrl(queueName))
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .visibilityTimeout(1)
                .messageAttributeNames("All")
                .messageSystemAttributeNames(MessageSystemAttributeName.ALL)).messages();
    }

    public static void purge(String queueName) {
        // PurgeQueue is rate limited on real SQS; on LocalStack it is immediate.
        sqs().purgeQueue(r -> r.queueUrl(queueUrl(queueName)));
    }

    public static void putStock(String sku, int stock) {
        dynamo().putItem(r -> r.tableName("inventory").item(Map.of(
                "sku", AttributeValue.fromS(sku),
                "stock", AttributeValue.fromN(Integer.toString(stock)))));
    }

    public static int stockOf(String sku) {
        Map<String, AttributeValue> item = dynamo().getItem(r -> r.tableName("inventory")
                .key(Map.of("sku", AttributeValue.fromS(sku))).consistentRead(true)).item();
        return Integer.parseInt(item.get("stock").n());
    }
}
