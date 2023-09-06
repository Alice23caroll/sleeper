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
package sleeper.ingest.testutils;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import org.apache.datasketches.quantiles.ItemsSketch;
import org.apache.hadoop.conf.Configuration;

import sleeper.core.key.Key;
import sleeper.core.partition.PartitionTree;
import sleeper.core.partition.PartitionsBuilder;
import sleeper.core.record.Record;
import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.LongType;
import sleeper.core.statestore.FileInfo;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.statestore.inmemory.StateStoreTestBuilder;
import sleeper.ingest.impl.IngestCoordinator;
import sleeper.ingest.impl.ParquetConfiguration;
import sleeper.ingest.impl.partitionfilewriter.DirectPartitionFileWriterFactory;
import sleeper.ingest.impl.partitionfilewriter.PartitionFileWriterFactory;
import sleeper.ingest.impl.recordbatch.RecordBatchFactory;
import sleeper.ingest.impl.recordbatch.arrow.ArrowRecordBatchFactory;
import sleeper.ingest.impl.recordbatch.arrow.ArrowRecordWriter;
import sleeper.ingest.impl.recordbatch.arrow.ArrowRecordWriterAcceptingRecords;
import sleeper.statestore.dynamodb.DynamoDBStateStore;
import sleeper.statestore.dynamodb.DynamoDBStateStoreCreator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.ingest.testutils.IngestCoordinatorTestHelper.parquetConfiguration;
import static sleeper.ingest.testutils.IngestCoordinatorTestHelper.standardIngestCoordinator;
import static sleeper.ingest.testutils.ResultVerifier.assertOnSketch;
import static sleeper.ingest.testutils.ResultVerifier.createFieldToItemSketchMap;
import static sleeper.ingest.testutils.ResultVerifier.readFieldToItemSketchMap;
import static sleeper.ingest.testutils.ResultVerifier.readMergedRecordsFromPartitionDataFiles;

public class IngestTestHelper<T> {

    private final Path temporaryFolder;
    private final Configuration hadoopConfiguration;
    private final String localWorkingDirectory;
    private final Schema schema;
    private final List<Record> expectedRecords;
    private Iterable<T> toWrite;
    private StateStore stateStore;
    private RecordBatchFactory<T> recordBatchFactory;
    private PartitionFileWriterFactory partitionFileWriterFactory;

    private IngestTestHelper(Path temporaryFolder, Configuration hadoopConfiguration,
                             Schema schema, List<Record> expectedRecords, Iterable<T> toWrite) throws Exception {
        this.temporaryFolder = temporaryFolder;
        this.hadoopConfiguration = hadoopConfiguration;
        this.localWorkingDirectory = createTempDirectory(temporaryFolder, null).toString();
        this.schema = schema;
        this.expectedRecords = expectedRecords;
        this.toWrite = toWrite;
    }

    public static IngestTestHelper<Record> from(
            Path temporaryFolder, Configuration hadoopConfiguration,
            RecordGenerator.RecordListAndSchema recordListAndSchema) throws Exception {
        return from(temporaryFolder, hadoopConfiguration,
                recordListAndSchema.sleeperSchema, recordListAndSchema.recordList);
    }

    public static IngestTestHelper<Record> from(
            Path temporaryFolder, Configuration hadoopConfiguration,
            Schema schema, List<Record> records) throws Exception {
        return new IngestTestHelper<>(temporaryFolder, hadoopConfiguration, schema, records, records);
    }

    public void ingestAndVerify(
            Function<Key, Integer> keyToPartitionNoMappingFn,
            Map<Integer, Integer> partitionNoToExpectedNoOfFilesMap) throws Exception {

        try (IngestCoordinator<T> ingestCoordinator = standardIngestCoordinator(
                stateStore, schema, recordBatchFactory, partitionFileWriterFactory)) {
            for (T write : toWrite) {
                ingestCoordinator.write(write);
            }
        }

        verify(keyToPartitionNoMappingFn, partitionNoToExpectedNoOfFilesMap);
    }

    public IngestTestHelper<T> createStateStore(
            AmazonDynamoDB dynamoDbClient,
            PartitionTree tree) throws Exception {
        DynamoDBStateStore stateStore = new DynamoDBStateStoreCreator(UUID.randomUUID().toString(), schema, dynamoDbClient).create();
        stateStore.initialise(tree.getAllPartitions());
        return stateStore(stateStore);
    }

    public IngestTestHelper<T> stateStoreInMemory(
            Consumer<PartitionsBuilder> partitionsConfig) {
        return stateStoreInMemory(partitionsConfig, files -> {
        });
    }

    public IngestTestHelper<T> stateStoreInMemory(
            Consumer<PartitionsBuilder> partitionsConfig,
            Consumer<StateStoreTestBuilder> filesConfig) {
        PartitionsBuilder partitions = new PartitionsBuilder(schema);
        partitionsConfig.accept(partitions);
        StateStoreTestBuilder builder = StateStoreTestBuilder.from(partitions);
        filesConfig.accept(builder);
        return stateStore(builder.buildStateStore());
    }

    public IngestTestHelper<T> directWrite() throws IOException {
        ParquetConfiguration parquetConfiguration = parquetConfiguration(schema, hadoopConfiguration);
        String ingestToDirectory = createTempDirectory(temporaryFolder, null).toString();
        return partitionFileWriterFactory(
                DirectPartitionFileWriterFactory.from(parquetConfiguration, ingestToDirectory));
    }

    public IngestTestHelper<Record> backedByArrow(
            Consumer<ArrowRecordBatchFactory.Builder<Record>> configuration) {
        return backedByArrowWithRecordWriter(new ArrowRecordWriterAcceptingRecords(), configuration);
    }

    public <N> IngestTestHelper<N> backedByArrowWithRecordWriter(
            ArrowRecordWriter<N> recordWriter,
            Consumer<ArrowRecordBatchFactory.Builder<N>> configuration) {
        ArrowRecordBatchFactory.Builder<N> builder = ArrowRecordBatchFactory.builder().schema(schema)
                .maxNoOfRecordsToWriteToArrowFileAtOnce(128)
                .localWorkingDirectory(localWorkingDirectory)
                .recordWriter(recordWriter);
        configuration.accept(builder);
        return recordBatchFactory(builder.build());
    }

    public IngestTestHelper<T> stateStore(StateStore stateStore) {
        this.stateStore = stateStore;
        return this;
    }

    public <N> IngestTestHelper<N> toWrite(Iterable<N> toWrite) {
        this.toWrite = (Iterable<T>) toWrite;
        return (IngestTestHelper<N>) this;
    }

    public <N> IngestTestHelper<N> recordBatchFactory(RecordBatchFactory<N> recordBatchFactory) {
        this.recordBatchFactory = (RecordBatchFactory<T>) recordBatchFactory;
        return (IngestTestHelper<N>) this;
    }

    public IngestTestHelper<T> partitionFileWriterFactory(PartitionFileWriterFactory partitionFileWriterFactory) {
        this.partitionFileWriterFactory = partitionFileWriterFactory;
        return this;
    }

    private void verify(
            Function<Key, Integer> keyToPartitionNoMappingFn,
            Map<Integer, Integer> partitionNoToExpectedNoOfFilesMap) throws StateStoreException {
        assertThat(Path.of(localWorkingDirectory)).isEmptyDirectory();

        PartitionTree partitionTree = new PartitionTree(schema, stateStore.getAllPartitions());

        Map<Integer, List<Record>> partitionNoToExpectedRecordsMap = expectedRecords.stream()
                .collect(Collectors.groupingBy(
                        record -> keyToPartitionNoMappingFn.apply(Key.create(record.getValues(schema.getRowKeyFieldNames())))));

        Map<String, List<FileInfo>> partitionIdToFileInfosMap = stateStore.getActiveFiles().stream()
                .collect(Collectors.groupingBy(FileInfo::getPartitionId));

        Map<String, Integer> partitionIdToPartitionNoMap = partitionNoToExpectedRecordsMap.entrySet().stream()
                .map(entry -> {
                    Key keyOfFirstRecord = Key.create(entry.getValue().get(0).getValues(schema.getRowKeyFieldNames()));
                    return new AbstractMap.SimpleEntry<>(partitionTree.getLeafPartition(keyOfFirstRecord).getId(), entry.getKey());
                }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<Integer, List<FileInfo>> partitionNoToFileInfosMap = partitionIdToFileInfosMap.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> partitionIdToPartitionNoMap.get(entry.getKey()),
                        Map.Entry::getValue));

        int expectedTotalNoOfFiles = partitionNoToExpectedNoOfFilesMap.values().stream()
                .mapToInt(Integer::valueOf)
                .sum();

        Set<Integer> allPartitionNoSet = Stream.of(
                        partitionNoToFileInfosMap.keySet().stream(),
                        partitionNoToExpectedNoOfFilesMap.keySet().stream(),
                        partitionNoToExpectedRecordsMap.keySet().stream())
                .flatMap(Function.identity())
                .collect(Collectors.toSet());

        assertThat(stateStore.getActiveFiles()).hasSize(expectedTotalNoOfFiles);
        assertThat(allPartitionNoSet).allMatch(partitionNoToExpectedNoOfFilesMap::containsKey);

        allPartitionNoSet.forEach(partitionNo -> verifyPartition(
                partitionNoToFileInfosMap.getOrDefault(partitionNo, Collections.emptyList()),
                partitionNoToExpectedNoOfFilesMap.get(partitionNo),
                partitionNoToExpectedRecordsMap.getOrDefault(partitionNo, Collections.emptyList())));
    }

    private void verifyPartition(List<FileInfo> partitionFileInfoList,
                                 int expectedNoOfFiles,
                                 List<Record> expectedRecords) {
        List<Record> actualRecords = readMergedRecordsFromPartitionDataFiles(schema, partitionFileInfoList, hadoopConfiguration);

        assertThat(partitionFileInfoList).hasSize(expectedNoOfFiles); // Asserts that the partitionFileInfoList has the same size as the expected number of files
        assertThat(actualRecords).containsExactlyInAnyOrderElementsOf(expectedRecords); // Asserts that the expected record list, when sorted, is identical to the record list in the partition

        // In some situations, check that the file min and max match the min and max of dimension 0
        if (expectedNoOfFiles == 1 &&
                schema.getRowKeyFields().get(0).getType() instanceof LongType) {
            String rowKeyFieldNameDimension0 = schema.getRowKeyFieldNames().get(0);
            Key minRowKeyDimension0 = expectedRecords.stream()
                    .map(record -> (Long) record.get(rowKeyFieldNameDimension0))
                    .min(Comparator.naturalOrder())
                    .map(Key::create)
                    .orElseThrow();
            Key maxRowKeyDimension0 = expectedRecords.stream()
                    .map(record -> (Long) record.get(rowKeyFieldNameDimension0))
                    .max(Comparator.naturalOrder())
                    .map(Key::create)
                    .orElseThrow();
            partitionFileInfoList.forEach(fileInfo -> {
                assertThat(fileInfo.getMinRowKey()).isEqualTo(minRowKeyDimension0);
                assertThat(fileInfo.getMaxRowKey()).isEqualTo(maxRowKeyDimension0);
            });
        }

        if (expectedNoOfFiles > 0) {
            Map<Field, ItemsSketch> expectedFieldToItemsSketchMap = createFieldToItemSketchMap(schema, expectedRecords);
            Map<Field, ItemsSketch> savedFieldToItemsSketchMap = readFieldToItemSketchMap(schema, partitionFileInfoList, hadoopConfiguration);
            schema.getRowKeyFields().forEach(field ->
                    assertOnSketch(field, expectedFieldToItemsSketchMap.get(field), savedFieldToItemsSketchMap.get(field)));
        }
    }
}
