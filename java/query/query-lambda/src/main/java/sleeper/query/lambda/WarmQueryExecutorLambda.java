/*
 * Copyright 2022-2024 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sleeper.query.lambda;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sleeper.configuration.jars.ObjectFactoryException;
import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.configuration.properties.table.S3TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.core.range.Range;
import sleeper.core.range.Region;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.ByteArrayType;
import sleeper.core.schema.type.IntType;
import sleeper.core.schema.type.ListType;
import sleeper.core.schema.type.LongType;
import sleeper.core.schema.type.MapType;
import sleeper.core.schema.type.StringType;
import sleeper.core.schema.type.Type;
import sleeper.query.model.Query;
import sleeper.query.model.QueryProcessingConfig;
import sleeper.query.model.QuerySerDe;
import sleeper.query.output.ResultsOutputConstants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.CONFIG_BUCKET;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.QUERY_QUEUE_URL;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;
import static sleeper.query.runner.output.NoResultsOutput.NO_RESULTS_OUTPUT;

/**
 * A Lambda that is triggered when an {@link ScheduledEvent} is received. A processor executes, creates a new
 * {@link Query} for every table in the system and publishes a {@link SendMessageRequest} to the SQS query queue.
 * The results from the queries are discarded as they are not required since this Lambda ensures that the query Lambdas
 * remain warm for genuine queries.
 */
@SuppressWarnings("unused")
public class WarmQueryExecutorLambda implements RequestHandler<ScheduledEvent, Void> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarmQueryExecutorLambda.class);

    private Instant lastUpdateTime;
    private InstanceProperties instanceProperties;
    private TablePropertiesProvider tablePropertiesProvider;
    private final AmazonSQS sqsClient;
    private final AmazonS3 s3Client;
    private final AmazonDynamoDB dynamoClient;
    private QueryMessageHandler messageHandler;
    private SqsQueryProcessor processor;

    public WarmQueryExecutorLambda() throws ObjectFactoryException {
        this(AmazonS3ClientBuilder.defaultClient(), AmazonSQSClientBuilder.defaultClient(),
                AmazonDynamoDBClientBuilder.defaultClient(), System.getenv(CONFIG_BUCKET.toEnvironmentVariable()));
    }

    public WarmQueryExecutorLambda(AmazonS3 s3Client, AmazonSQS sqsClient, AmazonDynamoDB dynamoClient, String configBucket) throws ObjectFactoryException {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.dynamoClient = dynamoClient;
        instanceProperties = loadInstanceProperties(s3Client, configBucket);
        tablePropertiesProvider = new TablePropertiesProvider(instanceProperties, s3Client, dynamoClient);
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        LOGGER.info("Starting to build queries for the tables");
        S3TableProperties.getStore(instanceProperties, s3Client, dynamoClient)
                .streamAllTables()
                .forEach(tableProperty -> {
                    Schema schema = tableProperty.getSchema();
                    Field field = schema.getRowKeyFields().get(0);

                    // Create the max and min values for the query. We don't care what they are as long as the query runs
                    Type type = field.getType();
                    Object min, max;
                    if (type instanceof IntType) {
                        min = 0;
                        max = 1;
                    } else if (type instanceof LongType) {
                        min = 0;
                        max = 1;
                    } else if (type instanceof StringType) {
                        min = "a";
                        max = "aa";
                    } else if (type instanceof ByteArrayType) {
                        min = new ByteArrayType();
                        max = new ByteArrayType();
                    } else if (type instanceof MapType) {
                        min = new HashMap<>();
                        max = new HashMap<>();
                    } else if (type instanceof ListType) {
                        min = new ArrayList<>();
                        max = new ArrayList<>();
                    } else {
                        throw new IllegalArgumentException("Unknown type in the schema: " + type);
                    }

                    Region region = new Region(Collections.singletonList(new Range.RangeFactory(schema)
                            .createRange(field, min, max)));

                    QuerySerDe querySerDe = new QuerySerDe(schema);
                    Query query = Query.builder()
                            .queryId(UUID.randomUUID().toString())
                            .tableName(tableProperty.get(TABLE_NAME))
                            .regions(List.of(region))
                            .processingConfig(QueryProcessingConfig.builder()
                                    .resultsPublisherConfig(Collections.singletonMap(ResultsOutputConstants.DESTINATION, NO_RESULTS_OUTPUT))
                                    .statusReportDestinations(Collections.emptyList())
                                    .build())
                            .build();

                    LOGGER.info("Query to be sent: " + querySerDe.toJson(query));
                    SendMessageRequest message = new SendMessageRequest(instanceProperties.get(QUERY_QUEUE_URL), querySerDe.toJson(query));
                    LOGGER.debug("Message: {}", message);
                    sqsClient.sendMessage(message);
        });
        return null;
    }

    private static InstanceProperties loadInstanceProperties(AmazonS3 s3Client, String configBucket) {
        InstanceProperties properties = new InstanceProperties();
        properties.loadFromS3(s3Client, configBucket);
        return properties;
    }
}
