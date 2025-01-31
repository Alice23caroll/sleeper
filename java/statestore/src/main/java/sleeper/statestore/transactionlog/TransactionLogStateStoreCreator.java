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
package sleeper.statestore.transactionlog;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.BillingMode;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.s3.AmazonS3;

import sleeper.configuration.properties.instance.InstanceProperties;

import java.util.List;

import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.DATA_BUCKET;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.FILE_TRANSACTION_LOG_TABLENAME;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.PARTITION_TRANSACTION_LOG_TABLENAME;

public class TransactionLogStateStoreCreator {
    private final AmazonDynamoDB dynamoDB;
    private final AmazonS3 s3Client;
    private final InstanceProperties instanceProperties;

    public TransactionLogStateStoreCreator(InstanceProperties instanceProperties, AmazonDynamoDB dynamoDB, AmazonS3 s3Client) {
        this.dynamoDB = dynamoDB;
        this.s3Client = s3Client;
        this.instanceProperties = instanceProperties;
    }

    public void create() {
        createTransactionLogTable(instanceProperties.get(FILE_TRANSACTION_LOG_TABLENAME));
        createTransactionLogTable(instanceProperties.get(PARTITION_TRANSACTION_LOG_TABLENAME));
        s3Client.createBucket(instanceProperties.get(DATA_BUCKET));
    }

    private void createTransactionLogTable(String tableName) {
        List<AttributeDefinition> attributeDefinitions = List.of(
                new AttributeDefinition(DynamoDBTransactionLogStateStore.TABLE_ID, ScalarAttributeType.S),
                new AttributeDefinition(DynamoDBTransactionLogStateStore.TRANSACTION_NUMBER, ScalarAttributeType.N));
        List<KeySchemaElement> keySchemaElements = List.of(
                new KeySchemaElement(DynamoDBTransactionLogStateStore.TABLE_ID, KeyType.HASH),
                new KeySchemaElement(DynamoDBTransactionLogStateStore.TRANSACTION_NUMBER, KeyType.RANGE));
        CreateTableRequest request = new CreateTableRequest()
                .withTableName(tableName)
                .withAttributeDefinitions(attributeDefinitions)
                .withKeySchema(keySchemaElements)
                .withBillingMode(BillingMode.PAY_PER_REQUEST);
        dynamoDB.createTable(request);
    }
}
