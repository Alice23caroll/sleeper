/*
 * Copyright 2022 Crown Copyright
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
package sleeper.systemtest.compaction;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sleeper.compaction.job.CompactionJobStatusStore;
import sleeper.compaction.status.store.job.DynamoDBCompactionJobStatusStore;
import sleeper.systemtest.SystemTestProperties;
import sleeper.systemtest.util.TriggerLambda;

import java.io.IOException;

import static sleeper.configuration.properties.SystemDefinedInstanceProperty.COMPACTION_JOB_CREATION_LAMBDA_FUNCTION;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.PARTITION_SPLITTING_LAMBDA_FUNCTION;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.SPLITTING_COMPACTION_TASK_CREATION_LAMBDA_FUNCTION;

public class SplitPartitionsUntilNoMoreSplits {
    private static final Logger LOGGER = LoggerFactory.getLogger(SplitPartitionsUntilNoMoreSplits.class);

    private SplitPartitionsUntilNoMoreSplits() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.out.println("Usage: <instance id> <table name>");
            return;
        }

        String instanceId = args[0];
        String tableName = args[1];

        AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
        AmazonDynamoDB dynamoDBClient = AmazonDynamoDBClientBuilder.defaultClient();

        SystemTestProperties systemTestProperties = new SystemTestProperties();
        systemTestProperties.loadFromS3GivenInstanceId(s3Client, instanceId);
        CompactionJobStatusStore store = DynamoDBCompactionJobStatusStore.from(dynamoDBClient, systemTestProperties);
        WaitForCompactionJobs wait = new WaitForCompactionJobs(store, tableName);

        while (true) {
            LOGGER.info("Splitting partitions");
            TriggerLambda.forInstance(instanceId, PARTITION_SPLITTING_LAMBDA_FUNCTION);
            LOGGER.info("Creating splitting compaction jobs");
            TriggerLambda.forInstance(instanceId, COMPACTION_JOB_CREATION_LAMBDA_FUNCTION);
            if (store.getUnfinishedJobs(tableName).isEmpty()) {
                LOGGER.info("Created no more jobs, splitting complete");
                break;
            }
            LOGGER.info("Creating splitting compaction tasks");
            TriggerLambda.forInstance(instanceId, SPLITTING_COMPACTION_TASK_CREATION_LAMBDA_FUNCTION);
            wait.pollUntilFinished();
        }
    }
}
