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
package sleeper.clients;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import sleeper.clients.util.ClientUtils;
import sleeper.clients.util.console.ConsoleInput;
import sleeper.clients.util.console.ConsoleOutput;
import sleeper.configuration.properties.instance.CdkDefinedInstanceProperty;
import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.configuration.table.index.DynamoDBTableIndex;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.table.TableIndex;
import sleeper.core.util.LoggedDuration;
import sleeper.query.model.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class QueryWebSocketCommandLineClient extends QueryCommandLineClient {
    private final String apiUrl;
    private final QueryWebSocketClient queryWebSocketClient;
    private final Supplier<Instant> timeSupplier;

    private QueryWebSocketCommandLineClient(
            InstanceProperties instanceProperties, TableIndex tableIndex, TablePropertiesProvider tablePropertiesProvider,
            ConsoleInput in, ConsoleOutput out) {
        this(instanceProperties, tableIndex, tablePropertiesProvider, in, out,
                new QueryWebSocketClient(instanceProperties, tablePropertiesProvider), Instant::now);
    }

    private QueryWebSocketCommandLineClient(
            InstanceProperties instanceProperties, TableIndex tableIndex, TablePropertiesProvider tablePropertiesProvider,
            ConsoleInput in, ConsoleOutput out, QueryWebSocketClient client, Supplier<Instant> timeSupplier) {
        this(instanceProperties, tableIndex, tablePropertiesProvider, in, out, client, () -> UUID.randomUUID().toString(), timeSupplier);
    }

    QueryWebSocketCommandLineClient(
            InstanceProperties instanceProperties, TableIndex tableIndex, TablePropertiesProvider tablePropertiesProvider,
            ConsoleInput in, ConsoleOutput out, QueryWebSocketClient client, Supplier<String> queryIdSupplier,
            Supplier<Instant> timeSupplier) {
        super(instanceProperties, tableIndex, tablePropertiesProvider, in, out, queryIdSupplier);

        this.apiUrl = instanceProperties.get(CdkDefinedInstanceProperty.QUERY_WEBSOCKET_API_URL);
        if (this.apiUrl == null) {
            throw new IllegalArgumentException("Use of this query client requires the WebSocket API to have been deployed as part of your Sleeper instance!");
        }
        this.queryWebSocketClient = client;
        this.timeSupplier = timeSupplier;
    }

    @Override
    protected void init(TableProperties tableProperties) {
    }

    @Override
    protected void submitQuery(TableProperties tableProperties, Query query) throws InterruptedException {
        Instant startTime = timeSupplier.get();
        long recordsReturned = 0L;
        try {
            out.println("Submitting query with ID: " + query.getQueryId());
            List<String> results = queryWebSocketClient.submitQuery(query).join();
            out.println("Query results:");
            results.forEach(out::println);
            recordsReturned = results.size();
        } catch (CompletionException e) {
            out.println("Query failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            out.println("Query failed: " + e.getMessage());
            throw e;
        } finally {
            out.println("Query took " + LoggedDuration.withFullOutput(startTime, timeSupplier.get()) + " to return " + recordsReturned + " records");
        }
    }

    public static void main(String[] args) throws StateStoreException, InterruptedException {
        if (1 != args.length) {
            throw new IllegalArgumentException("Usage: <instance-id>");
        }

        AmazonS3 amazonS3 = AmazonS3ClientBuilder.defaultClient();
        AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.defaultClient();
        InstanceProperties instanceProperties = ClientUtils.getInstanceProperties(amazonS3, args[0]);

        QueryWebSocketCommandLineClient client = new QueryWebSocketCommandLineClient(instanceProperties,
                new DynamoDBTableIndex(instanceProperties, dynamoDBClient),
                new TablePropertiesProvider(instanceProperties, amazonS3, dynamoDBClient),
                new ConsoleInput(System.console()), new ConsoleOutput(System.out));
        client.run();
    }
}
