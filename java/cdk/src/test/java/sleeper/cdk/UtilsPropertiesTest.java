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
package sleeper.cdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.local.SaveLocalProperties;
import sleeper.configuration.properties.table.TableProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.cdk.Utils.getVersion;
import static sleeper.cdk.UtilsTestHelper.cdkContextWithPropertiesFileAndSkipVersionCheck;
import static sleeper.cdk.UtilsTestHelper.createUserDefinedInstanceProperties;
import static sleeper.cdk.UtilsTestHelper.createUserDefinedTableProperties;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.BULK_IMPORT_BUCKET;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.VERSION;
import static sleeper.configuration.properties.UserDefinedInstanceProperty.ID;
import static sleeper.configuration.properties.table.TableProperty.DATA_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

class UtilsPropertiesTest {

    @TempDir
    private Path tempDir;

    @Nested
    @DisplayName("Load user defined properties from local configuration")
    class LoadUserDefinedProperties {

        @Test
        void shouldLoadValidInstancePropertiesFromFile() throws IOException {
            // Given
            InstanceProperties properties = createUserDefinedInstanceProperties();
            SaveLocalProperties.saveToDirectory(tempDir, properties, Stream.empty());

            // When / Then
            properties.set(VERSION, getVersion());
            assertThat(Utils.loadInstanceProperties(new InstanceProperties(),
                    cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir)))
                    .isEqualTo(properties);
        }

        @Test
        void shouldLoadValidTablePropertiesFromFile() throws IOException {
            // Given
            InstanceProperties instanceProperties = createUserDefinedInstanceProperties();
            TableProperties properties = createUserDefinedTableProperties(instanceProperties);
            SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.of(properties));

            // When / Then
            assertThat(Utils.getAllTableProperties(instanceProperties,
                    cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir)))
                    .containsExactly(properties);
        }

        @Test
        void shouldClearSystemDefinedPropertiesWhenInstancePropertiesAreLoaded() throws IOException {
            // Given
            InstanceProperties properties = createUserDefinedInstanceProperties();
            properties.set(BULK_IMPORT_BUCKET, "test-bulk-import-bucket");
            SaveLocalProperties.saveToDirectory(tempDir, properties, Stream.empty());

            // When
            InstanceProperties loaded = Utils.loadInstanceProperties(new InstanceProperties(),
                    cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir));

            // Then
            assertThat(loaded.get(BULK_IMPORT_BUCKET)).isNull();
        }

        @Test
        void shouldClearSystemDefinedPropertiesWhenTablePropertiesAreLoaded() throws IOException {
            // Given
            InstanceProperties instanceProperties = createUserDefinedInstanceProperties();
            TableProperties properties = createUserDefinedTableProperties(instanceProperties);
            properties.set(DATA_BUCKET, "test-data-bucket");
            SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.of(properties));

            // When
            Stream<TableProperties> loaded = Utils.getAllTableProperties(instanceProperties,
                    cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir));

            // Then
            assertThat(loaded)
                    .extracting(tableProperties -> tableProperties.get(DATA_BUCKET))
                    .containsExactly((String) null);
        }

        @Test
        void shouldSetVersionWhenInstancePropertiesAreLoaded() throws IOException {
            // Given
            InstanceProperties properties = createUserDefinedInstanceProperties();
            SaveLocalProperties.saveToDirectory(tempDir, properties, Stream.empty());

            // When
            InstanceProperties loaded = Utils.loadInstanceProperties(new InstanceProperties(),
                    cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir));

            // Then
            assertThat(loaded.get(VERSION))
                    .matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        }
    }

    @Nested
    @DisplayName("Ensure configuration will result in valid AWS resource names")
    class ValidateResourceNames {

        @Test
        void shouldFailWhenInstanceIdIsNotAValidBucketName() throws IOException {
            // Given
            InstanceProperties instanceProperties = createUserDefinedInstanceProperties();
            instanceProperties.set(ID, "aa$$aa");
            SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.empty());

            // When / Then
            InstanceProperties load = new InstanceProperties();
            Function<String, String> context = cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir);
            assertThatThrownBy(() -> Utils.loadInstanceProperties(load, context))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sleeper instance id is illegal: aa$$aa");
        }

        @Test
        void shouldFailWhenTableNameCannotBePartOfAValidBucketName() throws IOException {
            // Given
            InstanceProperties instanceProperties = createUserDefinedInstanceProperties();
            TableProperties properties = createUserDefinedTableProperties(instanceProperties);
            instanceProperties.set(ID, "valid-id");
            properties.set(TABLE_NAME, "example--invalid-name-tab$$-le");
            SaveLocalProperties.saveToDirectory(tempDir, instanceProperties, Stream.of(properties));

            // When / Then
            InstanceProperties load = new InstanceProperties();
            Function<String, String> context = cdkContextWithPropertiesFileAndSkipVersionCheck(tempDir);
            assertThatThrownBy(() -> Utils.loadInstanceProperties(load, context))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sleeper table bucket name is illegal: sleeper-valid-id-table-example--invalid-name-tab$$-le");
        }
    }
}
