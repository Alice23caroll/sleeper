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

package sleeper.statestore.s3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sleeper.core.schema.type.LongType;
import sleeper.core.statestore.AllReferencesToAFile;
import sleeper.core.statestore.AllReferencesToAllFiles;
import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.SplitFileReferenceRequest;
import sleeper.core.statestore.SplitFileReferences;
import sleeper.core.statestore.SplitRequestsFailedException;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.statestore.exception.FileAlreadyExistsException;
import sleeper.core.statestore.exception.FileHasReferencesException;
import sleeper.core.statestore.exception.FileNotFoundException;
import sleeper.core.statestore.exception.FileReferenceAlreadyExistsException;
import sleeper.core.statestore.exception.FileReferenceAssignedToJobException;
import sleeper.core.statestore.exception.FileReferenceNotAssignedToJobException;
import sleeper.core.statestore.exception.FileReferenceNotFoundException;
import sleeper.core.statestore.exception.NewReferenceSameAsOldReferenceException;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.core.schema.SchemaTestHelper.schemaWithKey;
import static sleeper.core.statestore.AllReferencesToAFileTestHelper.fileWithNoReferences;
import static sleeper.core.statestore.AllReferencesToAFileTestHelper.fileWithReferences;
import static sleeper.core.statestore.AssignJobIdRequest.assignJobOnPartitionToFiles;
import static sleeper.core.statestore.FileReferenceTestData.AFTER_DEFAULT_UPDATE_TIME;
import static sleeper.core.statestore.FileReferenceTestData.DEFAULT_UPDATE_TIME;
import static sleeper.core.statestore.FileReferenceTestData.splitFile;
import static sleeper.core.statestore.FileReferenceTestData.withJobId;
import static sleeper.core.statestore.FileReferenceTestData.withLastUpdate;
import static sleeper.core.statestore.FilesReportTestHelper.activeFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.noFiles;
import static sleeper.core.statestore.FilesReportTestHelper.noFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.partialReadyForGCFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.readyForGCFilesReport;
import static sleeper.core.statestore.SplitFileReferenceRequest.splitFileToChildPartitions;

public class S3FileReferenceStoreIT extends S3StateStoreOneTableTestBase {

    @BeforeEach
    void setUp() throws Exception {
        initialiseWithSchema(schemaWithKey("key", new LongType()));
    }

    @Nested
    @DisplayName("Handle ingest")
    class HandleIngest {

        @Test
        public void shouldAddAndReadActiveFiles() throws Exception {
            // Given
            Instant fixedUpdateTime = Instant.parse("2023-10-04T14:08:00Z");
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            FileReference file3 = factory.rootFile("file3", 100L);

            // When
            store.fixFileUpdateTime(fixedUpdateTime);
            store.addFile(file1);
            store.addFiles(List.of(file2, file3));

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(file1, file2, file3);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactlyInAnyOrder(file1, file2, file3);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files -> assertThat(files).containsExactlyInAnyOrder("file1", "file2", "file3"));
        }

        @Test
        void shouldSetLastUpdateTimeForFile() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference file = factory.rootFile("file1", 100L);

            // When
            store.fixFileUpdateTime(updateTime);
            store.addFile(file);

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(withLastUpdate(updateTime, file));
        }

        @Test
        void shouldAddFileSplitOverTwoPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference rootFile = factory.rootFile("file1", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.fixFileUpdateTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));

            // When / Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    withLastUpdate(updateTime, leftFile),
                    withLastUpdate(updateTime, rightFile));
        }

        @Test
        void shouldAddFileWithReferencesSplitOverTwoPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference rootFile = factory.rootFile("file1", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.fixFileUpdateTime(updateTime);
            store.addFilesWithReferences(List.of(fileWithReferences(List.of(leftFile, rightFile))));

            // When / Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    withLastUpdate(updateTime, leftFile),
                    withLastUpdate(updateTime, rightFile));
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(updateTime, leftFile, rightFile));
            assertThat(store.getReadyForGCFilenamesBefore(updateTime.plus(Duration.ofDays(1))))
                    .isEmpty();
            assertThat(store.hasNoFiles()).isFalse();
        }

        @Test
        void shouldAddTwoFilesWithReferences() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference leftFile1 = splitFile(file1, "L");
            FileReference rightFile1 = splitFile(file1, "R");
            FileReference file2 = factory.rootFile("file2", 100L);
            store.fixFileUpdateTime(updateTime);
            store.addFilesWithReferences(List.of(
                    fileWithReferences(List.of(leftFile1, rightFile1)),
                    fileWithReferences(List.of(file2))));

            // When / Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    withLastUpdate(updateTime, leftFile1),
                    withLastUpdate(updateTime, rightFile1),
                    withLastUpdate(updateTime, file2));
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(updateTime, leftFile1, rightFile1, file2));
            assertThat(store.getReadyForGCFilenamesBefore(updateTime.plus(Duration.ofDays(1))))
                    .isEmpty();
            assertThat(store.hasNoFiles()).isFalse();
        }

        @Test
        void shouldAddFileWithNoReferencesForGC() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            store.fixFileUpdateTime(updateTime);
            store.addFilesWithReferences(List.of(fileWithNoReferences("test-file")));

            // When / Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(readyForGCFilesReport(updateTime, "test-file"));
            assertThat(store.getReadyForGCFilenamesBefore(updateTime.plus(Duration.ofDays(1))))
                    .containsExactly("test-file");
            assertThat(store.hasNoFiles()).isFalse();
        }

        @Test
        void shouldFailToAddSameFileTwice() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference file = factory.rootFile("file1", 100L);
            store.fixFileUpdateTime(updateTime);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.addFile(file))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(withLastUpdate(updateTime, file));
            assertThatThrownBy(() -> store.addFilesWithReferences(List.of(fileWithReferences(file))))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(withLastUpdate(updateTime, file));
        }

        @Test
        void shouldFailToAddAnotherReferenceForSameFile() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file1", 100L);
            FileReference leftFile = splitFile(file, "L");
            FileReference rightFile = splitFile(file, "R");
            store.addFile(leftFile);

            // When / Then
            assertThatThrownBy(() -> store.addFile(rightFile))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(store.getFileReferences()).containsExactly(leftFile);
            assertThatThrownBy(() -> store.addFilesWithReferences(List.of(fileWithReferences(rightFile))))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(store.getFileReferences()).containsExactly(leftFile);
        }
    }

    @Nested
    @DisplayName("Split file references across multiple partitions")
    class SplitFiles {
        @Test
        void shouldSplitOneFileInRootPartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);

            // When
            SplitFileReferences.from(store).split();

            // Then
            List<FileReference> expectedReferences = List.of(splitFile(file, "L"), splitFile(file, "R"));
            assertThat(store.getFileReferences())
                    .containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldSplitTwoFilesInOnePartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            store.addFiles(List.of(file1, file2));

            // When
            SplitFileReferences.from(store).split();

            // Then
            List<FileReference> expectedReferences = List.of(
                    splitFile(file1, "L"),
                    splitFile(file1, "R"),
                    splitFile(file2, "L"),
                    splitFile(file2, "R"));
            assertThat(store.getFileReferences())
                    .containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldSplitOneFileFromTwoOriginalPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            splitPartition("L", "LL", "LR", 2);
            splitPartition("R", "RL", "RR", 7);
            FileReference file = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(file, "L");
            FileReference rightFile = splitFile(file, "R");
            store.addFiles(List.of(leftFile, rightFile));

            // When
            SplitFileReferences.from(store).split();

            // Then
            List<FileReference> expectedReferences = List.of(
                    splitFile(leftFile, "LL"),
                    splitFile(leftFile, "LR"),
                    splitFile(rightFile, "RL"),
                    splitFile(rightFile, "RR"));
            assertThat(store.getFileReferences())
                    .containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldSplitFilesInDifferentPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            splitPartition("L", "LL", "LR", 2);
            splitPartition("R", "RL", "RR", 7);
            FileReference file1 = factory.partitionFile("L", "file1", 100L);
            FileReference file2 = factory.partitionFile("R", "file2", 200L);
            store.addFiles(List.of(file1, file2));

            // When
            SplitFileReferences.from(store).split();

            // Then
            List<FileReference> expectedReferences = List.of(
                    splitFile(file1, "LL"),
                    splitFile(file1, "LR"),
                    splitFile(file2, "RL"),
                    splitFile(file2, "RR"));
            assertThat(store.getFileReferences())
                    .containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldOnlyPerformOneLevelOfSplits() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5L);
            splitPartition("L", "LL", "LR", 2L);
            splitPartition("R", "RL", "RR", 7L);
            FileReference file = factory.rootFile("file.parquet", 100L);
            store.addFile(file);

            // When
            SplitFileReferences.from(store).split();

            // Then
            List<FileReference> expectedReferences = List.of(
                    splitFile(file, "L"),
                    splitFile(file, "R"));
            assertThat(store.getFileReferences())
                    .containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldNotSplitOneFileInLeafPartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5L);
            FileReference file = factory.partitionFile("L", "already-split.parquet", 100L);
            store.addFile(file);

            // When
            SplitFileReferences.from(store).split();

            // Then
            assertThat(store.getFileReferences())
                    .containsExactly(file);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, file));
        }

        @Test
        void shouldDoNothingWhenNoFilesExist() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);

            // When
            SplitFileReferences.from(store).split();

            // Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(noFilesReport());
        }

        @Test
        void shouldFailToSplitFileWhichDoesNotExist() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);

            // When / Then
            assertThatThrownBy(() -> store.splitFileReferences(List.of(
                    splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(SplitRequestsFailedException.class)
                    .hasCauseInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(noFilesReport());
        }

        @Test
        void shouldFailToSplitFileWhenReferenceDoesNotExistInPartition() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            FileReference existingReference = splitFile(file, "L");
            store.addFile(existingReference);

            // When / Then
            assertThatThrownBy(() -> store.splitFileReferences(List.of(
                    splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(SplitRequestsFailedException.class)
                    .hasCauseInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(existingReference);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, existingReference));
        }

        @Test
        void shouldFailToSplitFileWhenTheOriginalFileWasSplitIncorrectlyToMultipleLevels() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);
            splitPartition("L", "LL", "LR", 2);
            FileReference file = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(file, "L");
            FileReference nestedFile = splitFile(leftFile, "LL");
            store.addFile(file);

            // Ideally this would fail as this produces duplicate references to the same records,
            // but not all state stores may be able to implement that
            store.splitFileReferences(List.of(new SplitFileReferenceRequest(file, List.of(leftFile, nestedFile))));

            // When / Then
            assertThatThrownBy(() -> SplitFileReferences.from(store).split())
                    .isInstanceOf(SplitRequestsFailedException.class)
                    .hasCauseInstanceOf(FileReferenceAlreadyExistsException.class);
            List<FileReference> expectedReferences = List.of(leftFile, nestedFile);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldThrowExceptionWhenSplittingFileHasBeenAssignedToTheJob() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file"))));

            // When / Then
            assertThatThrownBy(() -> store.splitFileReferences(List.of(splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(SplitRequestsFailedException.class)
                    .hasCauseInstanceOf(FileReferenceAssignedToJobException.class);
            assertThat(store.getFileReferences())
                    .containsExactly(withJobId("job1", file));
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, withJobId("job1", file)));
        }
    }

    @Nested
    @DisplayName("Create compaction jobs")
    class CreateCompactionJobs {

        @Test
        public void shouldMarkFileWithJobId() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);

            // When
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job", "root", List.of("file"))));

            // Then
            assertThat(store.getFileReferences()).containsExactly(withJobId("job", file));
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
        }

        @Test
        public void shouldMarkOneHalfOfSplitFileWithJobId() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            FileReference left = splitFile(file, "L");
            FileReference right = splitFile(file, "R");
            store.addFiles(List.of(left, right));

            // When
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job", "L", List.of("file"))));

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(withJobId("job", left), right);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(right);
        }

        @Test
        public void shouldMarkMultipleFilesWithJobIds() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            store.addFiles(List.of(file1, file2));

            // When
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file1")),
                    assignJobOnPartitionToFiles("job2", "root", List.of("file2"))));

            // Then
            assertThat(store.getFileReferences()).containsExactly(
                    withJobId("job1", file1),
                    withJobId("job2", file2));
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenOneIsAlreadySet() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file"))));

            // When / Then
            assertThatThrownBy(() -> store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job2", "root", List.of("file")))))
                    .isInstanceOf(FileReferenceAssignedToJobException.class);
            assertThat(store.getFileReferences()).containsExactly(withJobId("job1", file));
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
        }

        @Test
        public void shouldNotUpdateOtherFilesIfOneFileAlreadyHasJobId() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            FileReference file3 = factory.rootFile("file3", 100L);
            store.addFiles(List.of(file1, file2, file3));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file2"))));

            // When / Then
            assertThatThrownBy(() -> store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job2", "root", List.of("file1", "file2", "file3")))))
                    .isInstanceOf(FileReferenceAssignedToJobException.class);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    file1, withJobId("job1", file2), file3);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactlyInAnyOrder(file1, file3);
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenFileDoesNotExist() throws Exception {
            // Given
            FileReference file = factory.rootFile("existingFile", 100L);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("requestedFile")))))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(file);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(file);
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenFileDoesNotExistAndStoreIsEmpty() throws Exception {
            // When / Then
            assertThatThrownBy(() -> store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file")))))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenReferenceDoesNotExistInPartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            FileReference existingReference = splitFile(file, "L");
            store.addFile(existingReference);

            // When / Then
            assertThatThrownBy(() -> store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file")))))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(existingReference);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(existingReference);
        }
    }

    @Nested
    @DisplayName("Apply compaction")
    class ApplyCompaction {

        @Test
        public void shouldSetFileReadyForGC() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);

            // When
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("oldFile"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "root", List.of("oldFile"), newFile);

            // Then
            assertThat(store.getFileReferences()).containsExactly(newFile);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(newFile);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("oldFile");
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files -> assertThat(files).containsExactly("newFile"));
        }

        @Test
        void shouldFailToSetReadyForGCWhenAlreadyReadyForGC() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);

            // When
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("oldFile"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "root", List.of("oldFile"), newFile);

            // Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne("job1", "root", List.of("oldFile"), newFile))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(newFile);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(newFile);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("oldFile");
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files -> assertThat(files).containsExactly("newFile"));
        }

        @Test
        void shouldFailWhenFilesToMarkAsReadyForGCAreNotAssignedToJob() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("oldFile"), newFile))
                    .isInstanceOf(FileReferenceNotAssignedToJobException.class);
        }

        @Test
        public void shouldFailToSetFileReadyForGCWhichDoesNotExist() throws Exception {
            // Given
            FileReference newFile = factory.rootFile("newFile", 100L);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("oldFile"), newFile))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        public void shouldFailToSetFilesReadyForGCWhenOneDoesNotExist() throws Exception {
            // Given
            FileReference oldFile1 = factory.rootFile("oldFile1", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile1);
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("oldFile1"))));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("oldFile1", "oldFile2"), newFile))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(withJobId("job1", oldFile1));
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        public void shouldFailToSetFileReadyForGCWhenReferenceDoesNotExistInPartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            FileReference existingReference = splitFile(file, "L");
            store.addFile(existingReference);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("file"), factory.rootFile("file2", 100L)))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(existingReference);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        void shouldFailWhenFileToBeMarkedReadyForGCHasSameFileNameAsNewFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("file1", 100L);
            store.addFile(file);
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("file1"))));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("file1"), file))
                    .isInstanceOf(NewReferenceSameAsOldReferenceException.class);
            assertThat(store.getFileReferences()).containsExactly(withJobId("job1", file));
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        public void shouldFailWhenOutputFileAlreadyExists() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("oldFile", 100L);
            FileReference existingReference = splitFile(file, "L");
            FileReference newReference = factory.partitionFile("L", "newFile", 100L);
            store.addFiles(List.of(existingReference, newReference));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("oldFile"))));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "L", List.of("oldFile"), newReference))
                    .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    withJobId("job1", existingReference), newReference);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Find files for garbage collection")
    class FindFilesForGarbageCollection {

        @Test
        public void shouldFindFileWithNoReferencesWhichWasUpdatedLongEnoughAgo() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:00Z");
            store.fixFileUpdateTime(updateTime);
            store.addFilesWithReferences(List.of(fileWithNoReferences("readyForGc")));

            // When / Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .containsExactly("readyForGc");
        }

        @Test
        public void shouldNotFindFileWhichWasMarkedReadyForGCTooRecently() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:07:00Z");
            store.fixFileUpdateTime(updateTime);
            store.addFilesWithReferences(List.of(fileWithNoReferences("readyForGc")));

            // When / Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .isEmpty();
        }

        @Test
        public void shouldNotFindFileWhichHasTwoReferencesAndOnlyOneWasMarkedAsReadyForGC() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:00Z");
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("splitFile", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference compactionOutputFile = factory.partitionFile("L", "compactedFile", 100L);
            store.fixFileUpdateTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("splitFile"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("splitFile"), compactionOutputFile);

            // When / Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .isEmpty();
        }

        @Test
        public void shouldFindFileWhichHasTwoReferencesAndBothWereMarkedAsReadyForGC() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:00Z");
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("readyForGc", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference leftOutputFile = factory.partitionFile("L", "leftOutput", 100L);
            FileReference rightOutputFile = factory.partitionFile("R", "rightOutput", 100L);
            store.fixFileUpdateTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("readyForGc")),
                    assignJobOnPartitionToFiles("job2", "R", List.of("readyForGc"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("readyForGc"), leftOutputFile);
            store.atomicallyReplaceFileReferencesWithNewOne("job2", "R", List.of("readyForGc"), rightOutputFile);

            // When / Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .containsExactly("readyForGc");
        }

        @Test
        public void shouldNotFindSplitFileWhenOnlyFirstReadyForGCUpdateIsOldEnough() throws Exception {
            // Given ingest, compactions and GC check happened in order
            Instant ingestTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant firstCompactionTime = Instant.parse("2023-10-04T14:09:00Z");
            Instant secondCompactionTime = Instant.parse("2023-10-04T14:10:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:30Z");

            // And we have partitions, input files and output files
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("readyForGc", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference leftOutputFile = factory.partitionFile("L", "leftOutput", 100L);
            FileReference rightOutputFile = factory.partitionFile("R", "rightOutput", 100L);

            // And ingest and compactions happened at the expected times
            store.fixFileUpdateTime(ingestTime);
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("readyForGc")),
                    assignJobOnPartitionToFiles("job2", "R", List.of("readyForGc"))));
            store.fixFileUpdateTime(firstCompactionTime);
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("readyForGc"), leftOutputFile);
            store.fixFileUpdateTime(secondCompactionTime);
            store.atomicallyReplaceFileReferencesWithNewOne("job2", "R", List.of("readyForGc"), rightOutputFile);

            // When / Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Apply garbage collection")
    class ApplyGarbageCollection {

        @Test
        public void shouldDeleteGarbageCollectedFile() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("oldFile"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "root", List.of("oldFile"), newFile);

            // When
            store.deleteGarbageCollectedFileReferenceCounts(List.of("oldFile"));

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        void shouldDeleteGarbageCollectedFileSplitAcrossTwoPartitions() throws Exception {
            // Given we have partitions, input files and output files for compactions
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference leftOutputFile = factory.partitionFile("L", "leftOutput", 100L);
            FileReference rightOutputFile = factory.partitionFile("R", "rightOutput", 100L);

            // And the file was ingested as two references, then compacted into each partition
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("file")),
                    assignJobOnPartitionToFiles("job2", "R", List.of("file"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("file"), leftOutputFile);
            store.atomicallyReplaceFileReferencesWithNewOne("job2", "R", List.of("file"), rightOutputFile);

            // When
            store.deleteGarbageCollectedFileReferenceCounts(List.of("file"));

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, leftOutputFile, rightOutputFile));
        }

        @Test
        public void shouldFailToDeleteActiveFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("test", 100L);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("test")))
                    .isInstanceOf(FileHasReferencesException.class);
        }

        @Test
        public void shouldFailToDeleteFileWhichWasNotAdded() {
            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("test")))
                    .isInstanceOf(FileNotFoundException.class);
        }

        @Test
        public void shouldFailToDeleteActiveFileWhenOneOfTwoSplitRecordsIsReadyForGC() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference leftOutputFile = factory.partitionFile("L", "leftOutput", 100L);
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("file"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("file"), leftOutputFile);

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("file")))
                    .isInstanceOf(FileHasReferencesException.class);
        }

        @Test
        public void shouldDeleteGarbageCollectedFileWhileIteratingThroughReadyForGCFiles() throws Exception {
            // Given
            FileReference oldFile1 = factory.rootFile("oldFile1", 100L);
            FileReference oldFile2 = factory.rootFile("oldFile2", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFiles(List.of(oldFile1, oldFile2));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "root", List.of("oldFile1", "oldFile2"))));
            store.atomicallyReplaceFileReferencesWithNewOne(
                    "job1", "root", List.of("oldFile1", "oldFile2"), newFile);

            // When
            Iterator<String> iterator = store.getReadyForGCFilenamesBefore(Instant.ofEpochMilli(Long.MAX_VALUE)).iterator();
            store.deleteGarbageCollectedFileReferenceCounts(List.of(iterator.next()));

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly(iterator.next());
            assertThat(iterator).isExhausted();
        }

        @Test
        public void shouldFailToDeleteActiveFileWhenAlsoDeletingReadyForGCFile() throws Exception {
            // Given
            FileReference activeFile = factory.rootFile("activeFile", 100L);
            store.addFilesWithReferences(List.of(
                    fileWithNoReferences("gcFile"),
                    fileWithReferences(List.of(activeFile))));

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("gcFile", "activeFile")))
                    .isInstanceOf(FileHasReferencesException.class);
            assertThat(store.getFileReferences())
                    .containsExactly(activeFile);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("gcFile");
        }
    }

    @Nested
    @DisplayName("Report file status")
    class ReportFileStatus {

        @Test
        void shouldReportOneActiveFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("test", 100L);
            store.addFile(file);

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, file));
        }

        @Test
        void shouldReportOneReadyForGCFile() throws Exception {
            // Given
            store.addFilesWithReferences(List.of(fileWithNoReferences("test")));

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(readyForGCFilesReport(DEFAULT_UPDATE_TIME, "test"));
        }

        @Test
        void shouldReportTwoActiveFiles() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            store.addFiles(List.of(file1, file2));

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, file1, file2));
        }

        @Test
        void shouldReportFileSplitOverTwoPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.addFiles(List.of(leftFile, rightFile));

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, leftFile, rightFile));
        }

        @Test
        void shouldReportFileSplitOverTwoPartitionsWithOneSideCompacted() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            FileReference outputFile = factory.partitionFile("L", 50L);
            store.addFiles(List.of(leftFile, rightFile));
            store.assignJobIds(List.of(
                    assignJobOnPartitionToFiles("job1", "L", List.of("file"))));
            store.atomicallyReplaceFileReferencesWithNewOne("job1", "L", List.of("file"), outputFile);

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, outputFile, rightFile));
        }

        @Test
        void shouldReportReadyForGCFilesWithLimit() throws Exception {
            // Given
            store.addFilesWithReferences(List.of(
                    fileWithNoReferences("test1"),
                    fileWithNoReferences("test2"),
                    fileWithNoReferences("test3")));

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(2);

            // Then
            assertThat(report).isEqualTo(partialReadyForGCFilesReport(DEFAULT_UPDATE_TIME, "test1", "test2"));
        }

        @Test
        void shouldReportReadyForGCFilesMeetingLimit() throws Exception {
            // Given
            store.addFilesWithReferences(List.of(
                    fileWithNoReferences("test1"),
                    fileWithNoReferences("test2")));

            // When
            AllReferencesToAllFiles report = store.getAllFilesWithMaxUnreferenced(2);

            // Then
            assertThat(report).isEqualTo(readyForGCFilesReport(DEFAULT_UPDATE_TIME, "test1", "test2"));
        }
    }

    @Nested
    @DisplayName("Get files by partition")
    class FilesByPartition {

        @Test
        public void shouldReturnMultipleFilesOnEachPartition() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile1 = factory.rootFile("rootFile1", 10);
            FileReference rootFile2 = factory.rootFile("rootFile2", 10);
            FileReference leftFile1 = factory.partitionFile("L", "leftFile1", 10);
            FileReference leftFile2 = factory.partitionFile("L", "leftFile2", 10);
            FileReference rightFile1 = factory.partitionFile("R", "rightFile1", 10);
            FileReference rightFile2 = factory.partitionFile("R", "rightFile2", 10);
            store.addFiles(List.of(rootFile1, rootFile2, leftFile1, leftFile2, rightFile1, rightFile2));

            // When / Then
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root", "L", "R")
                    .hasEntrySatisfying("root", values -> assertThat(values)
                            .containsExactlyInAnyOrder("rootFile1", "rootFile2"))
                    .hasEntrySatisfying("L", values -> assertThat(values)
                            .containsExactlyInAnyOrder("leftFile1", "leftFile2"))
                    .hasEntrySatisfying("R", values -> assertThat(values)
                            .containsExactlyInAnyOrder("rightFile1", "rightFile2"));
        }

        @Test
        public void shouldNotReturnPartitionsWithNoFiles() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.partitionFile("L", "file", 100);
            store.addFile(file);

            // When / Then
            assertThat(store.getPartitionToReferencedFilesMap())
                    .isEqualTo(Map.of("L", List.of("file")));
        }
    }

    @Nested
    @DisplayName("Clear files")
    class ClearFiles {
        @Test
        void shouldDeleteReferencedFileOnClear() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);

            // When
            store.clearSleeperTable();
            store.initialise();

            // Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getPartitionToReferencedFilesMap()).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100)).isEqualTo(noFiles());
        }

        @Test
        void shouldDeleteUnreferencedFileOnClear() throws Exception {
            // Given
            store.addFilesWithReferences(List.of(AllReferencesToAFile.builder()
                    .filename("file")
                    .internalReferences(List.of())
                    .build()));

            // When
            store.clearSleeperTable();
            store.initialise();

            // Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getPartitionToReferencedFilesMap()).isEmpty();
            assertThat(store.getAllFilesWithMaxUnreferenced(100)).isEqualTo(noFiles());
        }
    }
}
