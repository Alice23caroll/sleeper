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
package sleeper.core.statestore.inmemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sleeper.core.partition.PartitionsBuilder;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.LongType;
import sleeper.core.statestore.AllReferencesToAllFiles;
import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.FileReferenceFactory;
import sleeper.core.statestore.SplitFileReference;
import sleeper.core.statestore.SplitFileReferences;
import sleeper.core.statestore.StateStore;
import sleeper.core.statestore.StateStoreException;
import sleeper.core.statestore.exception.FileNotAssignedToJobException;
import sleeper.core.statestore.exception.FileNotFoundException;
import sleeper.core.statestore.exception.FileReferenceNotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.core.schema.SchemaTestHelper.schemaWithKey;
import static sleeper.core.statestore.FilesReportTestHelper.activeFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.noFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.partialReadyForGCFilesReport;
import static sleeper.core.statestore.FilesReportTestHelper.readyForGCFilesReport;
import static sleeper.core.statestore.SplitFileReferenceRequest.splitFileToChildPartitions;
import static sleeper.core.statestore.inmemory.StateStoreTestHelper.inMemoryStateStoreWithNoPartitions;

public class InMemoryFileReferenceStoreTest {

    private static final Instant DEFAULT_UPDATE_TIME = Instant.parse("2023-10-04T14:08:00Z");
    private static final Instant AFTER_DEFAULT_UPDATE_TIME = DEFAULT_UPDATE_TIME.plus(Duration.ofMinutes(1));
    private final Schema schema = schemaWithKey("key", new LongType());
    private final PartitionsBuilder partitions = new PartitionsBuilder(schema).singlePartition("root");
    private FileReferenceFactory factory = FileReferenceFactory.fromUpdatedAt(partitions.buildTree(), DEFAULT_UPDATE_TIME);
    private final StateStore store = inMemoryStateStoreWithNoPartitions();

    @BeforeEach
    void setUp() {
        store.fixTime(DEFAULT_UPDATE_TIME);
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
            store.fixTime(fixedUpdateTime);
            store.addFile(file1);
            store.addFiles(List.of(file2, file3));

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(file1, file2, file3);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactlyInAnyOrder(file1, file2, file3);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files ->
                            assertThat(files).containsExactlyInAnyOrder("file1", "file2", "file3"));
        }

        @Test
        void shouldSetLastUpdateTimeForFileWhenFixingTimeCorrectly() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference file = factory.rootFile("file1", 100L);

            // When
            store.fixTime(updateTime);
            store.addFile(file);

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(withLastUpdate(updateTime, file));
        }

        @Test
        void shouldFailToAddSameFileTwice() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-12-01T10:45:00Z");
            FileReference file = factory.rootFile("file1", 100L);
            store.fixTime(updateTime);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.addFile(file))
                    .isInstanceOf(StateStoreException.class);
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
            store.fixTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));

            // When / Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    withLastUpdate(updateTime, leftFile),
                    withLastUpdate(updateTime, rightFile));
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
                    .isEqualTo(noFilesReport());
        }

        @Test
        void shouldFailToSplitFileWhichDoesNotExist() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);

            // When / Then
            assertThatThrownBy(() ->
                    store.splitFileReferences(List.of(
                            splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
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
            assertThatThrownBy(() ->
                    store.splitFileReferences(List.of(
                            splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getActiveFiles()).containsExactly(existingReference);
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, existingReference));
        }

        @Test
        void shouldFailToSplitFileWhenTheSameFileWasAddedBackToParentPartition() throws StateStoreException {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);
            SplitFileReferences.from(store).split();
            // Ideally this would fail as the file is already referenced in partitions below it,
            // but not all state stores may be able to implement that
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> SplitFileReferences.from(store).split())
                    .isInstanceOf(StateStoreException.class);
            List<FileReference> expectedReferences = List.of(
                    file,
                    splitFile(file, "L"),
                    splitFile(file, "R"));
            assertThat(store.getFileReferences()).containsExactlyInAnyOrderElementsOf(expectedReferences);
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, expectedReferences));
        }

        @Test
        void shouldThrowExceptionWhenSplittingFileThatHasBeenAssignedToTheJob() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));

            // When / Then
            assertThatThrownBy(() -> store.splitFileReferences(List.of(splitFileToChildPartitions(file, "L", "R"))))
                    .isInstanceOf(StateStoreException.class);
            assertThat(store.getFileReferences())
                    .containsExactly(file.toBuilder().jobId("job1").build());
            assertThat(store.getAllFileReferencesWithMaxUnreferenced(100))
                    .isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, file.toBuilder().jobId("job1").build()));
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
            store.atomicallyAssignJobIdToFileReferences("job", Collections.singletonList(file));

            // Then
            assertThat(store.getFileReferences()).containsExactly(file.toBuilder().jobId("job").build());
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
            store.atomicallyAssignJobIdToFileReferences("job", Collections.singletonList(left));

            // Then
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(left.toBuilder().jobId("job").build(), right);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(right);
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenOneIsAlreadySet() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);
            store.atomicallyAssignJobIdToFileReferences("job1", Collections.singletonList(file));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyAssignJobIdToFileReferences("job2", Collections.singletonList(file)))
                    .isInstanceOf(StateStoreException.class);
            assertThat(store.getFileReferences()).containsExactly(file.toBuilder().jobId("job1").build());
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
        }

        @Test
        public void shouldNotUpdateOtherFilesIfOneFileAlreadyHasJobId() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("file1", 100L);
            FileReference file2 = factory.rootFile("file2", 100L);
            FileReference file3 = factory.rootFile("file3", 100L);
            store.addFiles(Arrays.asList(file1, file2, file3));
            store.atomicallyAssignJobIdToFileReferences("job1", Collections.singletonList(file2));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyAssignJobIdToFileReferences("job2", Arrays.asList(file1, file2, file3)))
                    .isInstanceOf(StateStoreException.class);
            assertThat(store.getFileReferences()).containsExactlyInAnyOrder(
                    file1, file2.toBuilder().jobId("job1").build(), file3);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactlyInAnyOrder(file1, file3);
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenFileDoesNotExist() throws Exception {
            // Given
            FileReference file = factory.rootFile("existingFile", 100L);
            FileReference requested = factory.rootFile("requestedFile", 100L);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyAssignJobIdToFileReferences("job", List.of(requested)))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(file);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(file);
        }

        @Test
        public void shouldNotMarkFileWithJobIdWhenFileDoesNotExistAndStoreIsEmpty() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyAssignJobIdToFileReferences("job", List.of(file)))
                    .isInstanceOf(FileNotFoundException.class);
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
            assertThatThrownBy(() -> store.atomicallyUpdateJobStatusOfFiles("job", List.of(file)))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getActiveFiles()).containsExactly(existingReference);
            assertThat(store.getActiveFilesWithNoJobId()).containsExactly(existingReference);
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
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(oldFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("oldFile"), List.of(newFile));

            // Then
            assertThat(store.getFileReferences()).containsExactly(newFile);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(newFile);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("oldFile");
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files ->
                            assertThat(files).containsExactly("newFile"));
        }

        @Test
        void shouldFailToSetReadyForGCWhenAlreadyReadyForGC() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(oldFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("oldFile"), List.of(newFile));

            // Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("oldFile"), List.of(newFile)))
                    .isInstanceOf(StateStoreException.class);
            assertThat(store.getFileReferences()).containsExactly(newFile);
            assertThat(store.getFileReferencesWithNoJobId()).containsExactly(newFile);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("oldFile");
            assertThat(store.getPartitionToReferencedFilesMap())
                    .containsOnlyKeys("root")
                    .hasEntrySatisfying("root", files ->
                            assertThat(files).containsExactly("newFile"));
        }

        @Test
        public void shouldStillHaveAFileAfterSettingOnlyFileReadyForGC() throws Exception {
            // Given
            FileReference file = factory.rootFile("file", 100L);
            store.addFile(file);

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("file"), List.of());

            // Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME))
                    .containsExactly("file");
            assertThat(store.getPartitionToReferencedFilesMap()).isEmpty();
            assertThat(store.hasNoFiles()).isFalse();
        }

        @Test
        void shouldFailWhenFilesToMarkAsReadyForGCAreNotAssignedToJob() throws Exception {
            // Given
            FileReference oldFile = factory.rootFile("oldFile", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFile(oldFile);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("oldFile"), List.of(newFile)))
                    .isInstanceOf(FileNotAssignedToJobException.class);
        }

        @Test
        public void shouldFailToSetFileReadyForGCWhichDoesNotExist() throws Exception {
            // Given
            FileReference newFile = factory.rootFile("newFile", 100L);

            // When / Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("oldFile"), List.of(newFile)))
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
            store.atomicallyUpdateJobStatusOfFiles("job1", List.of(oldFile1));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("oldFile1", "oldFile2"), List.of(newFile)))
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(store.getFileReferences()).containsExactly(oldFile1.toBuilder().jobId("job1").build());
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
            assertThatThrownBy(() -> store.atomicallyUpdateFilesToReadyForGCAndCreateNewActiveFiles(
                    "job1", "root", List.of("file"), List.of(splitFile(file, "R"))))
                    .isInstanceOf(FileReferenceNotFoundException.class);
            assertThat(store.getActiveFiles()).containsExactly(existingReference);
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        void shouldThrowExceptionWhenFileToBeMarkedReadyForGCHasSameFileNameAsNewFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("file1", 100L);
            store.addFile(file);
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("file1"), List.of(file)))
                    .isInstanceOf(StateStoreException.class)
                    .hasMessage("File reference to be removed has same filename as new file: file1");
            assertThat(store.getFileReferences()).containsExactly(file.toBuilder().jobId("job1").build());
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        void shouldThrowExceptionWhenAddingNewFileReferencesThatReferenceTheSameFile() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference oldFile = factory.rootFile("file1", 100L);
            FileReference newFileReference1 = factory.partitionFile("L", "file2", 100L);
            FileReference newFileReference2 = factory.partitionFile("R", "file2", 100L);
            store.addFile(oldFile);
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(oldFile));

            // When / Then
            assertThatThrownBy(() -> store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("file1"), List.of(newFileReference1, newFileReference2)))
                    .isInstanceOf(StateStoreException.class)
                    .hasMessage("Multiple new file references reference the same file: file2");
            assertThat(store.getFileReferences()).containsExactly(oldFile.toBuilder().jobId("job1").build());
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Find files for garbage collection")
    class FindFilesForGarbageCollection {

        @Test
        public void shouldFindFileWhichWasMarkedReadyForGCLongEnoughAgo() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:00Z");
            FileReference file = factory.rootFile("readyForGc", 100L);
            store.fixTime(updateTime);
            store.addFile(file);

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("readyForGc"), List.of());

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .containsExactly("readyForGc");
        }

        @Test
        public void shouldNotFindFileWhichWasMarkedReadyForGCTooRecently() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:07:00Z");
            FileReference file = factory.rootFile("readyForGc", 100L);
            store.fixTime(updateTime);
            store.addFile(file);

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("readyForGc"), List.of());

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .isEmpty();
        }

        @Test
        public void shouldNotFindFileWhichHasTwoReferencesAndOnlyOneWasMarkedAsReadyForGC() throws Exception {
            // Given
            Instant updateTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:00Z");
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("readyForGc", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.fixTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("readyForGc"), List.of());

            // Then
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
            store.fixTime(updateTime);
            store.addFiles(List.of(leftFile, rightFile));

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("readyForGc"), List.of());
            store.atomicallyAssignJobIdToFileReferences("job2", List.of(rightFile));
            store.atomicallyApplyJobFileReferenceUpdates("job2", "R", List.of("readyForGc"), List.of());

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(latestTimeForGc))
                    .containsExactly("readyForGc");
        }

        @Test
        public void shouldNotFindSplitFileWhenOnlyFirstReadyForGCUpdateIsOldEnough() throws Exception {
            // Given
            Instant addTime = Instant.parse("2023-10-04T14:08:00Z");
            Instant readyForGc1Time = Instant.parse("2023-10-04T14:09:00Z");
            Instant readyForGc2Time = Instant.parse("2023-10-04T14:10:00Z");
            Instant latestTimeForGc = Instant.parse("2023-10-04T14:09:30Z");
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("readyForGc", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.fixTime(addTime);
            store.addFiles(List.of(leftFile, rightFile));

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyAssignJobIdToFileReferences("job2", List.of(rightFile));
            store.fixTime(readyForGc1Time);
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("readyForGc"), List.of());
            store.fixTime(readyForGc2Time);
            store.atomicallyApplyJobFileReferenceUpdates("job2", "R", List.of("readyForGc"), List.of());

            // Then
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
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(oldFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("oldFile"), List.of(newFile));

            // When
            store.deleteGarbageCollectedFileReferenceCounts(List.of("oldFile"));

            // Then
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
        }

        @Test
        void shouldDeleteGarbageCollectedFileSplitAcrossTwoPartitions() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.addFiles(List.of(leftFile, rightFile));

            // When
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("file"), List.of());
            store.atomicallyAssignJobIdToFileReferences("job2", List.of(rightFile));
            store.atomicallyApplyJobFileReferenceUpdates("job2", "R", List.of("file"), List.of());
            store.deleteGarbageCollectedFileReferenceCounts(List.of("file"));

            // Then
            assertThat(store.getFileReferences()).isEmpty();
            assertThat(store.getFileReferencesWithNoJobId()).isEmpty();
            assertThat(store.getReadyForGCFilenamesBefore(AFTER_DEFAULT_UPDATE_TIME)).isEmpty();
            assertThat(store.getPartitionToReferencedFilesMap()).isEmpty();
            assertThat(store.hasNoFiles()).isTrue();
        }

        @Test
        public void shouldFailToDeleteActiveFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("test", 100L);
            store.addFile(file);

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("test")))
                    .isInstanceOf(StateStoreException.class);
        }

        @Test
        public void shouldFailToDeleteFileWhichWasNotAdded() {
            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("test")))
                    .isInstanceOf(StateStoreException.class);
        }

        @Test
        public void shouldFailToDeleteActiveFileWhenOneOfTwoSplitRecordsIsReadyForGC() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.addFiles(List.of(leftFile, rightFile));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("file"), List.of());

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("file")))
                    .isInstanceOf(StateStoreException.class);
        }

        @Test
        public void shouldDeleteGarbageCollectedFileWhileIteratingThroughReadyForGCFiles() throws Exception {
            // Given
            FileReference oldFile1 = factory.rootFile("oldFile1", 100L);
            FileReference oldFile2 = factory.rootFile("oldFile2", 100L);
            FileReference newFile = factory.rootFile("newFile", 100L);
            store.addFiles(List.of(oldFile1, oldFile2));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(oldFile1, oldFile2));
            store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("oldFile1", "oldFile2"), List.of(newFile));

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
            FileReference gcFile = factory.rootFile("gcFile", 100L);
            FileReference activeFile = factory.rootFile("activeFile", 100L);
            store.addFiles(List.of(gcFile, activeFile));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(gcFile));
            store.atomicallyApplyJobFileReferenceUpdates(
                    "job1", "root", List.of("gcFile"), List.of());

            // When / Then
            assertThatThrownBy(() -> store.deleteGarbageCollectedFileReferenceCounts(List.of("gcFile", "activeFile")))
                    .isInstanceOf(StateStoreException.class);
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
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, file));
        }

        @Test
        void shouldReportOneReadyForGCFile() throws Exception {
            // Given
            FileReference file = factory.rootFile("test", 100L);
            store.addFile(file);
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("test"), List.of());

            // When
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(5);

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
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(5);

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
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, leftFile, rightFile));
        }

        @Test
        void shouldReportFileSplitOverTwoPartitionsWithOneReadyForGC() throws Exception {
            // Given
            splitPartition("root", "L", "R", 5);
            FileReference rootFile = factory.rootFile("file", 100L);
            FileReference leftFile = splitFile(rootFile, "L");
            FileReference rightFile = splitFile(rootFile, "R");
            store.addFiles(List.of(leftFile, rightFile));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(leftFile));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "L", List.of("file"), List.of());

            // When
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(5);

            // Then
            assertThat(report).isEqualTo(activeFilesReport(DEFAULT_UPDATE_TIME, rightFile));
        }

        @Test
        void shouldReportReadyForGCFilesWithLimit() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("test1", 100L);
            FileReference file2 = factory.rootFile("test2", 100L);
            FileReference file3 = factory.rootFile("test3", 100L);
            store.addFiles(List.of(file1, file2, file3));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file1, file2, file3));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("test1", "test2", "test3"), List.of());

            // When
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(2);

            // Then
            assertThat(report).isEqualTo(partialReadyForGCFilesReport(DEFAULT_UPDATE_TIME, "test1", "test2"));
        }

        @Test
        void shouldReportReadyForGCFilesMeetingLimit() throws Exception {
            // Given
            FileReference file1 = factory.rootFile("test1", 100L);
            FileReference file2 = factory.rootFile("test2", 100L);
            store.addFiles(List.of(file1, file2));
            store.atomicallyAssignJobIdToFileReferences("job1", List.of(file1, file2));
            store.atomicallyApplyJobFileReferenceUpdates("job1", "root", List.of("test1", "test2"), List.of());

            // When
            AllReferencesToAllFiles report = store.getAllFileReferencesWithMaxUnreferenced(2);

            // Then
            assertThat(report).isEqualTo(readyForGCFilesReport(DEFAULT_UPDATE_TIME, "test1", "test2"));
        }
    }

    private void splitPartition(String parentId, String leftId, String rightId, long splitPoint) throws StateStoreException {
        partitions.splitToNewChildren(parentId, leftId, rightId, splitPoint)
                .applySplit(store, parentId);
        factory = FileReferenceFactory.fromUpdatedAt(partitions.buildTree(), DEFAULT_UPDATE_TIME);
    }

    private FileReference splitFile(FileReference parentFile, String childPartitionId) {
        return SplitFileReference.referenceForChildPartition(parentFile, childPartitionId)
                .toBuilder().lastStateStoreUpdateTime(DEFAULT_UPDATE_TIME).build();
    }

    private static FileReference withLastUpdate(Instant updateTime, FileReference file) {
        return file.toBuilder().lastStateStoreUpdateTime(updateTime).build();
    }
}
