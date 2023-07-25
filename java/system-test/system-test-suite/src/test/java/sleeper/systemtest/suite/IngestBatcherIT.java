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

package sleeper.systemtest.suite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import sleeper.core.record.Record;
import sleeper.core.util.PollWithRetries;
import sleeper.systemtest.suite.dsl.SleeperSystemTest;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.table.TableProperty.BULK_IMPORT_MIN_LEAF_PARTITION_COUNT;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_INGEST_MODE;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MAX_JOB_FILES;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MIN_JOB_FILES;
import static sleeper.configuration.properties.table.TableProperty.INGEST_BATCHER_MIN_JOB_SIZE;
import static sleeper.configuration.properties.validation.BatchIngestMode.BULK_IMPORT_EMR;
import static sleeper.configuration.properties.validation.BatchIngestMode.STANDARD_INGEST;
import static sleeper.systemtest.suite.fixtures.SystemTestInstance.MAIN;
import static sleeper.systemtest.suite.fixtures.SystemTestRecords.recordsForRange;

@Tag("SystemTest")
public class IngestBatcherIT {

    private final SleeperSystemTest sleeper = SleeperSystemTest.getInstance();

    @BeforeEach
    void setUp() {
        sleeper.connectToInstance(MAIN);
        sleeper.ingest().batcher().clearStore();
    }

    @Test
    void shouldCreateTwoStandardIngestJobsWithMaxJobFilesOfThree() throws InterruptedException {
        // Given
        sleeper.updateTableProperties(tableProperties -> {
            tableProperties.set(INGEST_BATCHER_INGEST_MODE, STANDARD_INGEST.toString());
            tableProperties.set(INGEST_BATCHER_MIN_JOB_FILES, "1");
            tableProperties.set(INGEST_BATCHER_MIN_JOB_SIZE, "1K");
            tableProperties.set(INGEST_BATCHER_MAX_JOB_FILES, "3");
        });

        // When
        sleeper.sourceFiles()
                .create("file1.parquet", recordsForRange(0, 100))
                .create("file2.parquet", recordsForRange(100, 200))
                .create("file3.parquet", recordsForRange(200, 300))
                .create("file4.parquet", recordsForRange(300, 400));
        sleeper.ingest().batcher()
                .sendSourceFiles("file1.parquet", "file2.parquet", "file3.parquet", "file4.parquet")
                .invoke().waitForJobs();

        // Then
        assertThat(sleeper.directQuery().allRecordsInTable())
                .containsExactlyInAnyOrderElementsOf(recordsForRange(0, 400));
    }

    @Test
    void shouldBulkImportOneRecord() throws InterruptedException {
        // Given
        sleeper.updateTableProperties(tableProperties -> {
            tableProperties.set(INGEST_BATCHER_INGEST_MODE, BULK_IMPORT_EMR.toString());
            tableProperties.set(INGEST_BATCHER_MIN_JOB_FILES, "1");
            tableProperties.set(INGEST_BATCHER_MIN_JOB_SIZE, "1");
            tableProperties.set(BULK_IMPORT_MIN_LEAF_PARTITION_COUNT, "1");
        });
        Record record = new Record(Map.of(
                "key", "some-id",
                "timestamp", 1234L,
                "value", "Some value"));

        // When
        sleeper.sourceFiles().create("file.parquet", record);
        sleeper.ingest().batcher().sendSourceFiles("file.parquet").invoke().waitForJobs(
                PollWithRetries.intervalAndPollingTimeout(Duration.ofSeconds(30), Duration.ofMinutes(30)));

        // Then
        assertThat(sleeper.directQuery().allRecordsInTable())
                .containsExactly(record);
    }
}
