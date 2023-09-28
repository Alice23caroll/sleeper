/*
 * Copyright 2022-2023 Crown Copyright
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
package sleeper.statestore.s3;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import org.apache.hadoop.conf.Configuration;

import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TableProperty;
import sleeper.core.statestore.DelegatingStateStore;
import sleeper.core.statestore.StateStoreException;

import java.time.Instant;

import static sleeper.configuration.properties.instance.CommonProperty.FILE_SYSTEM;
import static sleeper.configuration.properties.instance.SystemDefinedInstanceProperty.DATA_BUCKET;
import static sleeper.configuration.properties.instance.SystemDefinedInstanceProperty.REVISION_TABLENAME;
import static sleeper.configuration.properties.table.TableProperty.GARBAGE_COLLECTOR_DELAY_BEFORE_DELETION;

/**
 * An implementation of StateStore that stores the information in Parquet files in S3. A DynamoDB table is
 * used as a lightweight consistency layer. The table stores a revision id for the current version of the files
 * information. This Dynamo value is conditionally updated when the state store is updated. If this conditional update
 * fails then the update is retried.
 */
public class S3StateStore extends DelegatingStateStore {
    public static final String TABLE_NAME = "TABLE_NAME";
    public static final String REVISION_ID_KEY = "REVISION_ID_KEY";
    public static final String CURRENT_PARTITIONS_REVISION_ID_KEY = "CURRENT_PARTITIONS_REVISION_ID_KEY";
    public static final String CURRENT_FILES_REVISION_ID_KEY = "CURRENT_FILES_REVISION_ID_KEY";
    public static final String CURRENT_REVISION = "CURRENT_REVISION";
    public static final String CURRENT_UUID = "CURRENT_UUID";
    public static final String FIRST_REVISION = S3StateStore.getZeroPaddedLong(1L);

    public S3StateStore(InstanceProperties instanceProperties,
                        TableProperties tableProperties,
                        AmazonDynamoDB dynamoDB,
                        Configuration conf) {
        super(S3FileInfoStore.builder()
                        .fs(instanceProperties.get(FILE_SYSTEM))
                        .s3Path(s3Path(instanceProperties, tableProperties))
                        .sleeperTable(tableProperties.get(TableProperty.TABLE_NAME))
                        .dynamoRevisionIdTable(instanceProperties.get(REVISION_TABLENAME))
                        .rowKeyTypes(tableProperties.getSchema().getRowKeyTypes())
                        .garbageCollectorDelayBeforeDeletionInMinutes(tableProperties.getInt(GARBAGE_COLLECTOR_DELAY_BEFORE_DELETION))
                        .dynamoDB(dynamoDB)
                        .conf(conf)
                        .build(),
                S3PartitionStore.builder()
                        .fs(instanceProperties.get(FILE_SYSTEM))
                        .s3Path(s3Path(instanceProperties, tableProperties))
                        .sleeperTable(tableProperties.get(TableProperty.TABLE_NAME))
                        .dynamoRevisionIdTable(instanceProperties.get(REVISION_TABLENAME))
                        .tableSchema(tableProperties.getSchema())
                        .dynamoDB(dynamoDB)
                        .conf(conf)
                        .build());
    }

    private static String s3Path(InstanceProperties instanceProperties, TableProperties tableProperties) {
        return instanceProperties.get(DATA_BUCKET) + "/" + tableProperties.get(TableProperty.TABLE_NAME);
    }

    public void setInitialFileInfos() throws StateStoreException {
        fileInfoStore.initialise();
    }

    protected static String getZeroPaddedLong(long number) {
        StringBuilder versionString = new StringBuilder("" + number);
        while (versionString.length() < 12) {
            versionString.insert(0, "0");
        }
        return versionString.toString();
    }

    public void fixTime(Instant now) {
        ((S3FileInfoStore) fileInfoStore).fixTime(now);
    }
}
