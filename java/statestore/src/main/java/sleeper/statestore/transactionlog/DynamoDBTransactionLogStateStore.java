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
import com.amazonaws.services.s3.AmazonS3;

import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.core.statestore.transactionlog.TransactionLogStateStore;

import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.FILE_TRANSACTION_LOG_TABLENAME;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.PARTITION_TRANSACTION_LOG_TABLENAME;

public class DynamoDBTransactionLogStateStore extends TransactionLogStateStore {
    public static final String TABLE_ID = "TABLE_ID";
    public static final String TRANSACTION_NUMBER = "TRANSACTION_NUMBER";

    public DynamoDBTransactionLogStateStore(
            InstanceProperties instanceProperties, TableProperties tableProperties, AmazonDynamoDB dynamoDB, AmazonS3 s3) {
        super(builderFrom(instanceProperties, tableProperties, dynamoDB, s3));
    }

    public static TransactionLogStateStore.Builder builderFrom(
            InstanceProperties instanceProperties, TableProperties tableProperties, AmazonDynamoDB dynamoDB, AmazonS3 s3) {
        return builder()
                .sleeperTable(tableProperties.getStatus())
                .schema(tableProperties.getSchema())
                .filesLogStore(new DynamoDBTransactionLogStore(instanceProperties.get(FILE_TRANSACTION_LOG_TABLENAME), instanceProperties, tableProperties, dynamoDB, s3))
                .partitionsLogStore(new DynamoDBTransactionLogStore(instanceProperties.get(PARTITION_TRANSACTION_LOG_TABLENAME), instanceProperties, tableProperties, dynamoDB, s3));
    }

}
