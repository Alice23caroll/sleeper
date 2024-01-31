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
import sleeper.core.statestore.FileReference;
import sleeper.core.statestore.SplitFileReferences;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.core.schema.SchemaTestHelper.schemaWithKey;

public class S3StateStoreNewIT extends S3StateStoreNewTestBase {

    @Nested
    @DisplayName("Handle S3-specific cases for file references")
    class FileReferences {
        @BeforeEach
        void setUp() throws Exception {
            initialiseWithSchema(schemaWithKey("key", new LongType()));
        }

        @Test
        public void shouldAddAndRetrieve1000FileReferences() throws Exception {
            // Given
            List<FileReference> files = IntStream.range(0, 1000)
                    .mapToObj(i -> factory.rootFile("file-" + i, 1))
                    .collect(Collectors.toUnmodifiableList());
            store.fixTime(Instant.ofEpochMilli(1_000_000L));

            // When
            store.addFiles(files);

            // Then
            assertThat(new HashSet<>(store.getFileReferences())).isEqualTo(files.stream()
                    .map(reference -> withLastUpdate(Instant.ofEpochMilli(1_000_000L), reference))
                    .collect(Collectors.toSet()));
        }

        @Test
        void shouldUseOneRevisionUpdateToSplitFilesInDifferentPartitions() throws Exception {
            // Given
            initialiseWithSchema(schemaWithKey("key", new LongType()));
            splitPartition("root", "L", "R", 5);
            splitPartition("L", "LL", "LR", 2);
            splitPartition("R", "RL", "RR", 7);
            FileReference file1 = factory.partitionFile("L", "file1", 100L);
            FileReference file2 = factory.partitionFile("R", "file2", 200L);
            store.addFiles(List.of(file1, file2));

            // When
            SplitFileReferences.from(store).split();

            // Then
            assertThat(getCurrentFilesRevision()).isEqualTo(versionWithPrefix("3"));
        }
    }

    private String getCurrentFilesRevision() {
        S3RevisionIdStore revisionStore = new S3RevisionIdStore(dynamoDBClient, instanceProperties, tableProperties);
        return revisionStore.getCurrentFilesRevisionId().getRevision();
    }

    private static String versionWithPrefix(String version) {
        return "00000000000" + version;
    }
}
