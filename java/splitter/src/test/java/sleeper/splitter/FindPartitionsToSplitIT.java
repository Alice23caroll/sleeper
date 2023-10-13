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
package sleeper.splitter;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import sleeper.configuration.properties.instance.InstanceProperties;
import sleeper.configuration.properties.table.FixedTablePropertiesProvider;
import sleeper.configuration.properties.table.TableProperties;
import sleeper.configuration.properties.table.TablePropertiesProvider;
import sleeper.core.CommonTestConstants;
import sleeper.core.record.Record;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.IntType;
import sleeper.core.statestore.FileInfo;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;
import sleeper.ingest.IngestRecordsFromIterator;
import sleeper.ingest.impl.IngestCoordinator;
import sleeper.ingest.impl.ParquetConfiguration;
import sleeper.ingest.impl.partitionfilewriter.DirectPartitionFileWriterFactory;
import sleeper.ingest.impl.recordbatch.arraylist.ArrayListRecordBatchFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.InstancePropertiesTestHelper.createTestInstanceProperties;
import static sleeper.configuration.properties.instance.CdkDefinedInstanceProperty.PARTITION_SPLITTING_QUEUE_URL;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTableProperties;
import static sleeper.configuration.properties.table.TableProperty.PARTITION_SPLIT_THRESHOLD;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;
import static sleeper.configuration.testutils.LocalStackAwsV1ClientHelper.buildAwsV1Client;
import static sleeper.core.statestore.inmemory.StateStoreTestHelper.inMemoryStateStoreWithSinglePartition;
import static sleeper.ingest.testutils.IngestCoordinatorTestHelper.parquetConfiguration;
import static sleeper.ingest.testutils.IngestCoordinatorTestHelper.standardIngestCoordinator;

@Testcontainers
public class FindPartitionsToSplitIT {
    @Container
    public static LocalStackContainer localStackContainer = new LocalStackContainer(DockerImageName.parse(CommonTestConstants.LOCALSTACK_DOCKER_IMAGE))
            .withServices(LocalStackContainer.Service.SQS);

    @TempDir
    public Path tempDir;

    private final AmazonSQS sqsClient = buildAwsV1Client(localStackContainer,
            LocalStackContainer.Service.SQS, AmazonSQSClientBuilder.standard());
    private static final Schema SCHEMA = Schema.builder().rowKeyFields(new Field("key", new IntType())).build();
    private final InstanceProperties instanceProperties = createTestInstanceProperties();
    private final TableProperties tableProperties = createTestTableProperties(instanceProperties, SCHEMA);
    private final StateStore stateStore = inMemoryStateStoreWithSinglePartition(SCHEMA);
    private final String tableName = tableProperties.get(TABLE_NAME);
    private final TablePropertiesProvider tablePropertiesProvider = new FixedTablePropertiesProvider(tableProperties);

    @BeforeEach
    void setUp() {
        String queueName = UUID.randomUUID().toString();
        CreateQueueResult queue = sqsClient.createQueue(queueName);
        instanceProperties.set(PARTITION_SPLITTING_QUEUE_URL, queue.getQueueUrl());
    }

    @Test
    public void shouldPutMessagesOnAQueueIfAPartitionSizeGoesBeyondThreshold() throws StateStoreException, IOException {
        // Given
        tableProperties.setNumber(PARTITION_SPLIT_THRESHOLD, 500);
        writeFiles(stateStore, SCHEMA, createEvenRecordList(100, 10));

        // When
        FindPartitionsToSplit partitionFinder = new FindPartitionsToSplit(tableName, tablePropertiesProvider,
                stateStore, 10, sqsClient, instanceProperties.get(PARTITION_SPLITTING_QUEUE_URL));

        partitionFinder.run();

        // Then
        List<Message> messages = receivePartitionSplittingMessages();
        assertThat(messages).hasSize(1);

        SplitPartitionJobDefinition job = new SplitPartitionJobDefinitionSerDe(tablePropertiesProvider)
                .fromJson(messages.get(0).getBody());

        assertThat(job.getFileNames()).hasSize(10);
        assertThat(job.getTableName()).isEqualTo(tableName);
        assertThat(job.getPartition()).isEqualTo(stateStore.getAllPartitions().get(0));
    }

    @Test
    public void shouldNotPutMessagesOnAQueueIfPartitionsAreAllUnderThreshold() throws StateStoreException, IOException {
        // Given
        tableProperties.setNumber(PARTITION_SPLIT_THRESHOLD, 1001);
        writeFiles(stateStore, SCHEMA, createEvenRecordList(100, 10));

        // When
        FindPartitionsToSplit partitionFinder = new FindPartitionsToSplit(tableName, tablePropertiesProvider,
                stateStore, 10, sqsClient, instanceProperties.get(PARTITION_SPLITTING_QUEUE_URL));

        partitionFinder.run();

        // The
        assertThat(receivePartitionSplittingMessages()).isEmpty();
    }

    @Test
    public void shouldLimitNumberOfFilesInJobAccordingToTheMaximum() throws IOException, StateStoreException {
        // Given
        tableProperties.setNumber(PARTITION_SPLIT_THRESHOLD, 500);
        writeFiles(stateStore, SCHEMA, createEvenRecordList(100, 10));

        // When
        FindPartitionsToSplit partitionFinder = new FindPartitionsToSplit(tableName, tablePropertiesProvider,
                stateStore, 5, sqsClient, instanceProperties.get(PARTITION_SPLITTING_QUEUE_URL));

        partitionFinder.run();

        // Then
        List<Message> messages = receivePartitionSplittingMessages();
        assertThat(messages).hasSize(1);

        SplitPartitionJobDefinition job = new SplitPartitionJobDefinitionSerDe(tablePropertiesProvider)
                .fromJson(messages.get(0).getBody());

        assertThat(job.getFileNames()).hasSize(5);
        assertThat(job.getTableName()).isEqualTo(tableName);
        assertThat(job.getPartition()).isEqualTo(stateStore.getAllPartitions().get(0));
    }

    @Test
    public void shouldPrioritiseFilesContainingTheLargestNumberOfRecords() throws StateStoreException, IOException {
        // Given
        tableProperties.setNumber(PARTITION_SPLIT_THRESHOLD, 500);
        writeFiles(stateStore, SCHEMA, createAscendingRecordList(100, 10));

        // When
        FindPartitionsToSplit partitionFinder = new FindPartitionsToSplit(tableName, tablePropertiesProvider,
                stateStore, 5, sqsClient, instanceProperties.get(PARTITION_SPLITTING_QUEUE_URL));

        partitionFinder.run();

        // Then
        List<Message> messages = receivePartitionSplittingMessages();
        assertThat(messages).hasSize(1);

        SplitPartitionJobDefinition job = new SplitPartitionJobDefinitionSerDe(tablePropertiesProvider)
                .fromJson(messages.get(0).getBody());

        assertThat(job.getFileNames()).hasSize(5);
        assertThat(job.getTableName()).isEqualTo(tableName);
        assertThat(job.getPartition()).isEqualTo(stateStore.getAllPartitions().get(0));

        List<FileInfo> activeFiles = stateStore.getActiveFiles();
        Optional<Long> numberOfRecords = job.getFileNames().stream().flatMap(fileName -> activeFiles.stream()
                .filter(fi -> fi.getFilename().equals(fileName))
                .map(FileInfo::getNumberOfRecords)).reduce(Long::sum);

        // 109 + 108 + 107 + 106 + 105 = 535
        assertThat(numberOfRecords).contains(535L);
    }

    private List<List<Record>> createEvenRecordList(Integer recordsPerList, Integer numberOfLists) {
        List<List<Record>> recordLists = new ArrayList<>();
        for (int i = 0; i < numberOfLists; i++) {
            List<Record> records = new ArrayList<>();
            for (int j = 0; j < recordsPerList; j++) {
                Record record = new Record();
                record.put("key", j);
                records.add(record);
            }
            recordLists.add(records);
        }

        return recordLists;
    }

    private List<List<Record>> createAscendingRecordList(Integer startingRecordsPerList, Integer numberOfLists) {
        List<List<Record>> recordLists = new ArrayList<>();
        Integer recordsPerList = startingRecordsPerList;
        for (int i = 0; i < numberOfLists; i++) {
            List<Record> records = new ArrayList<>();
            for (int j = 0; j < recordsPerList; j++) {
                Record record = new Record();
                record.put("key", j);
                records.add(record);
            }
            recordLists.add(records);
            recordsPerList++;
        }

        return recordLists;
    }

    private void writeFiles(StateStore stateStore, Schema schema, List<List<Record>> recordLists) {
        ParquetConfiguration parquetConfiguration = parquetConfiguration(schema, new Configuration());
        recordLists.forEach(list -> {
            try {
                File stagingArea = createTempDirectory(tempDir, null).toFile();
                File directory = createTempDirectory(tempDir, null).toFile();
                try (IngestCoordinator<Record> coordinator = standardIngestCoordinator(stateStore, schema,
                        ArrayListRecordBatchFactory.builder()
                                .parquetConfiguration(parquetConfiguration)
                                .localWorkingDirectory(stagingArea.getAbsolutePath())
                                .maxNoOfRecordsInMemory(1_000_000)
                                .maxNoOfRecordsInLocalStore(1000L)
                                .buildAcceptingRecords(),
                        DirectPartitionFileWriterFactory.from(parquetConfiguration,
                                "file://" + directory.getAbsolutePath())
                )) {
                    new IngestRecordsFromIterator(coordinator, list.iterator()).write();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<Message> receivePartitionSplittingMessages() {
        return sqsClient.receiveMessage(instanceProperties.get(PARTITION_SPLITTING_QUEUE_URL)).getMessages();
    }

    public static class TestTablePropertiesProvider extends TablePropertiesProvider {
        private final Schema schema;
        private final long splitThreshold;

        TestTablePropertiesProvider(Schema schema, long splitThreshold) {
            super(null, new InstanceProperties());
            this.schema = schema;
            this.splitThreshold = splitThreshold;
        }

        TestTablePropertiesProvider(Schema schema) {
            this(schema, 1_000_000_000L);
        }

        @Override
        public TableProperties getTableProperties(String tableName) {
            TableProperties tableProperties = new TableProperties(new InstanceProperties());
            tableProperties.setSchema(schema);
            tableProperties.set(PARTITION_SPLIT_THRESHOLD, "" + splitThreshold);
            return tableProperties;
        }
    }
}
