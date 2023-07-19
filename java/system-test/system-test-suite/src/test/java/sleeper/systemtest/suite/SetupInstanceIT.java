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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sleeper.core.record.Record;
import sleeper.systemtest.suite.dsl.SleeperSystemTest;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static sleeper.configuration.properties.instance.CommonProperty.RETAIN_INFRA_AFTER_DESTROY;
import static sleeper.systemtest.suite.fixtures.SystemTestInstance.MAIN;

@Tag("SystemTest")
public class SetupInstanceIT {
    @TempDir
    private Path tempDir;
    private final SleeperSystemTest sleeper = SleeperSystemTest.getInstance();

    @Test
    void shouldConnectToInstance() {
        // When
        sleeper.connectToInstance(MAIN);

        // Then
        assertThat(sleeper.instanceProperties().getBoolean(RETAIN_INFRA_AFTER_DESTROY))
                .isFalse();
    }

    @Test
    void shouldIngestData() throws Exception {
        // When
        sleeper.connectToInstance(MAIN);
        sleeper.ingestData(tempDir, Stream.of(new Record(
                Map.of("key", "value"))));

        // Then
        assertThat(sleeper.allRecordsInTable())
                .containsExactly(new Record(Map.of("key", "value")));
    }
}
