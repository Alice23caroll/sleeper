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

package sleeper.configuration.properties.table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sleeper.configuration.properties.instance.InstanceProperties;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sleeper.configuration.properties.InstancePropertiesTestHelper.createTestInstanceProperties;
import static sleeper.configuration.properties.instance.CommonProperty.TABLE_PROPERTIES_PROVIDER_TIMEOUT_IN_MINS;
import static sleeper.configuration.properties.table.TablePropertiesTestHelper.createTestTableProperties;
import static sleeper.configuration.properties.table.TableProperty.ROW_GROUP_SIZE;
import static sleeper.configuration.properties.table.TableProperty.TABLE_ID;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;
import static sleeper.core.schema.SchemaTestHelper.schemaWithKey;

public class TablePropertiesProviderTest {

    private final InstanceProperties instanceProperties = createTestInstanceProperties();
    private final TablePropertiesStore store = InMemoryTableProperties.getStore();
    private final TableProperties tableProperties = createValidTableProperties();
    private final String tableId = tableProperties.get(TABLE_ID);
    private final String tableName = tableProperties.get(TABLE_NAME);
    private final TablePropertiesProvider provider = new TablePropertiesProvider(instanceProperties, store, Instant::now);

    @Nested
    @DisplayName("Load table properties")
    class LoadProperties {

        @Test
        void shouldLoadByName() {
            // Given
            store.save(tableProperties);

            // When / Then
            assertThat(provider.getByName(tableName)).isEqualTo(tableProperties);
        }

        @Test
        void shouldLoadById() {
            // Given
            store.save(tableProperties);

            // When / Then
            assertThat(provider.getById(tableId))
                    .isEqualTo(tableProperties);
        }

        @Test
        void shouldLookupByName() {
            // Given
            store.save(tableProperties);

            // When / Then
            assertThat(provider.lookupByName(tableName))
                    .contains(tableProperties.getId());
        }

        @Test
        void shouldLoadByFullIdentifier() {
            // Given
            store.save(tableProperties);

            // When / Then
            assertThat(provider.get(tableProperties.getId()))
                    .isEqualTo(tableProperties);
        }

        @Test
        void shouldCacheWhenLookingUpByIdThenName() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123);
            store.save(tableProperties);

            provider.getById(tableId);

            tableProperties.setNumber(ROW_GROUP_SIZE, 456);
            store.save(tableProperties);

            // When / Then
            assertThat(provider.getByName(tableName).getInt(ROW_GROUP_SIZE))
                    .isEqualTo(123);
        }

        @Test
        void shouldCacheWhenLookingUpByNameThenId() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123);
            store.save(tableProperties);

            provider.getByName(tableName);

            tableProperties.setNumber(ROW_GROUP_SIZE, 456);
            store.save(tableProperties);

            // When / Then
            assertThat(provider.getById(tableId).getInt(ROW_GROUP_SIZE))
                    .isEqualTo(123);
        }

        @Test
        void shouldReportTableDoesNotExistWhenNotInBucket() {
            // When / Then
            assertThat(provider.lookupByName(tableName))
                    .isEmpty();
        }

        @Test
        void shouldThrowExceptionWhenTableDoesNotExist() {
            // When / Then
            assertThatThrownBy(() -> provider.getByName(tableName))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("Load all tables")
    class LoadAllTables {

        @Test
        void shouldLoadAllTables() {
            // Given
            TableProperties table1 = createValidTableProperties();
            TableProperties table2 = createValidTableProperties();
            table1.set(TABLE_NAME, "table-1");
            table2.set(TABLE_NAME, "table-2");
            store.save(table1);
            store.save(table2);

            // When / Then
            assertThat(provider.streamAllTables())
                    .containsExactly(table1, table2);
            assertThat(provider.streamAllTableIds())
                    .containsExactly(table1.getId(), table2.getId());
        }

        @Test
        void shouldCachePropertiesAfterLoadingAllTables() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123);
            store.save(tableProperties);

            provider.streamAllTables().forEach(properties -> {
            });

            tableProperties.setNumber(ROW_GROUP_SIZE, 456);
            store.save(tableProperties);

            // When / Then
            assertThat(provider.getByName(tableName).getInt(ROW_GROUP_SIZE))
                    .isEqualTo(123);
            assertThat(provider.getById(tableId).getInt(ROW_GROUP_SIZE))
                    .isEqualTo(123);
        }

        @Test
        void shouldRetrieveFromCacheWhenLoadingAllTables() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123);
            store.save(tableProperties);

            provider.getByName(tableName);

            tableProperties.setNumber(ROW_GROUP_SIZE, 456);
            store.save(tableProperties);

            // When / Then
            assertThat(provider.streamAllTables()
                    .map(properties -> properties.getInt(ROW_GROUP_SIZE)))
                    .contains(123);
        }
    }

    @Nested
    @DisplayName("Expire cached properties on a timeout")
    class ExpireCacheOnTimeout {

        @Test
        void shouldReloadPropertiesFromS3WhenTimeoutReachedForTable() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123L);
            store.save(tableProperties);
            instanceProperties.setNumber(TABLE_PROPERTIES_PROVIDER_TIMEOUT_IN_MINS, 3);
            TablePropertiesProvider provider = providerWithTimes(
                    Instant.parse("2023-10-09T17:11:00Z"),
                    Instant.parse("2023-10-09T17:15:00Z"));

            // When
            provider.getByName(tableName); // Populate cache
            tableProperties.setNumber(ROW_GROUP_SIZE, 456L);
            store.save(tableProperties);

            // Then
            assertThat(provider.getByName(tableName).getLong(ROW_GROUP_SIZE))
                    .isEqualTo(456L);
        }

        @Test
        void shouldNotReloadPropertiesWhenTimeoutHasNotBeenReachedForTable() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123L);
            store.save(tableProperties);
            instanceProperties.setNumber(TABLE_PROPERTIES_PROVIDER_TIMEOUT_IN_MINS, 3);
            TablePropertiesProvider provider = providerWithTimes(
                    Instant.parse("2023-10-09T17:11:00Z"),
                    Instant.parse("2023-10-09T17:12:00Z"));

            // When
            provider.getByName(tableName); // Populate cache
            tableProperties.setNumber(ROW_GROUP_SIZE, 456L);
            store.save(tableProperties);

            // Then
            assertThat(provider.getByName(tableName).getLong(ROW_GROUP_SIZE))
                    .isEqualTo(123L);
        }

        @Test
        void shouldLoadPropertiesThenHitCacheThenReloadOnExpiry() {
            // Given
            tableProperties.setNumber(ROW_GROUP_SIZE, 123);
            store.save(tableProperties);
            instanceProperties.setNumber(TABLE_PROPERTIES_PROVIDER_TIMEOUT_IN_MINS, 3);
            TablePropertiesProvider provider = providerWithTimes(
                    Instant.parse("2023-10-09T17:10:00Z"),
                    Instant.parse("2023-10-09T17:12:00Z"),
                    Instant.parse("2023-10-09T17:14:00Z"));

            // When
            int foundInitialLoad = provider.getByName(tableName).getInt(ROW_GROUP_SIZE);
            tableProperties.setNumber(ROW_GROUP_SIZE, 456);
            store.save(tableProperties);
            int foundCacheHit = provider.getByName(tableName).getInt(ROW_GROUP_SIZE);
            int foundCacheExpired = provider.getByName(tableName).getInt(ROW_GROUP_SIZE);

            // Then
            assertThat(List.of(foundInitialLoad, foundCacheHit, foundCacheExpired))
                    .containsExactly(123, 123, 456);
        }

        @Test
        void shouldNotReloadPropertiesWhenTimeoutHasBeenReachedForOtherTable() {
            // Given
            TableProperties tableProperties1 = createValidTableProperties();
            TableProperties tableProperties2 = createValidTableProperties();
            tableProperties1.setNumber(ROW_GROUP_SIZE, 123L);
            tableProperties2.setNumber(ROW_GROUP_SIZE, 123L);
            store.save(tableProperties1);
            store.save(tableProperties2);
            instanceProperties.setNumber(TABLE_PROPERTIES_PROVIDER_TIMEOUT_IN_MINS, 3);
            TablePropertiesProvider provider = providerWithTimes(
                    Instant.parse("2023-10-09T17:11:00Z"),
                    Instant.parse("2023-10-09T17:14:00Z"),
                    Instant.parse("2023-10-09T17:15:00Z"),
                    Instant.parse("2023-10-09T17:15:00Z"));

            // When
            provider.getByName(tableProperties1.get(TABLE_NAME)); // Populate cache
            provider.getByName(tableProperties2.get(TABLE_NAME)); // Populate cache
            tableProperties1.setNumber(ROW_GROUP_SIZE, 456L);
            tableProperties2.setNumber(ROW_GROUP_SIZE, 456L);
            store.save(tableProperties1);
            store.save(tableProperties2);

            // Then
            assertThat(provider.getByName(tableProperties1.get(TABLE_NAME)).getLong(ROW_GROUP_SIZE))
                    .isEqualTo(456L);
            assertThat(provider.getByName(tableProperties2.get(TABLE_NAME)).getLong(ROW_GROUP_SIZE))
                    .isEqualTo(123L);
        }
    }

    private TablePropertiesProvider providerWithTimes(Instant... times) {
        return new TablePropertiesProvider(instanceProperties, store, List.of(times).iterator()::next);
    }

    private TableProperties createValidTableProperties() {
        return createTestTableProperties(instanceProperties, schemaWithKey("key"));
    }
}
