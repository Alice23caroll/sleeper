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

package sleeper.configuration.properties.local;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import sleeper.configuration.properties.InstanceProperties;
import sleeper.configuration.properties.table.TableProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static sleeper.configuration.properties.SystemDefinedInstanceProperty.CONFIG_BUCKET;
import static sleeper.configuration.properties.SystemDefinedInstanceProperty.QUERY_RESULTS_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.DATA_BUCKET;
import static sleeper.configuration.properties.table.TableProperty.TABLE_NAME;

public class SaveLocalProperties {

    private SaveLocalProperties() {
    }

    public static void saveFromS3(AmazonS3 s3, String instanceId, Path directory) {
        InstanceProperties instanceProperties = new InstanceProperties();
        try {
            instanceProperties.loadFromS3GivenInstanceId(s3, instanceId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        saveToDirectory(directory, instanceProperties, loadTablesFromS3(s3, instanceProperties));
    }

    public static void saveToDirectory(Path directory,
                                       InstanceProperties instanceProperties,
                                       Stream<TableProperties> tablePropertiesStream) {
        try {
            instanceProperties.save(directory.resolve("instance.properties"));
            Files.writeString(directory.resolve("tags.properties"), instanceProperties.getTagsPropertiesAsString());
            writeStringIfSet(directory.resolve("configBucket.txt"), instanceProperties.get(CONFIG_BUCKET));
            writeStringIfSet(directory.resolve("queryResultsBucket.txt"), instanceProperties.get(QUERY_RESULTS_BUCKET));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        tablePropertiesStream.forEach(tableProperties -> {
            // Store in the same directory structure as in S3 (tables/table-name)
            Path tableDir = directory.resolve("tables").resolve(tableProperties.get(TABLE_NAME));
            try {
                Files.createDirectories(tableDir);
                tableProperties.save(tableDir.resolve("table.properties"));

                // Unpack properties for schema & table bucket
                tableProperties.getSchema().save(tableDir.resolve("schema.json"));
                Files.writeString(tableDir.resolve("tableBucket.txt"), tableProperties.get(DATA_BUCKET));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static void writeStringIfSet(Path file, String value) throws IOException {
        if (value != null) {
            Files.writeString(file, value);
        }
    }

    private static Stream<TableProperties> loadTablesFromS3(AmazonS3 s3, InstanceProperties instanceProperties) {
        Iterable<S3ObjectSummary> objects = S3Objects.withPrefix(
                s3, instanceProperties.get(CONFIG_BUCKET), "tables/");
        return StreamSupport.stream(objects.spliterator(), false)
                .map(tableConfigObject -> loadTableFromS3(s3, instanceProperties, tableConfigObject));
    }

    private static TableProperties loadTableFromS3(
            AmazonS3 s3, InstanceProperties instanceProperties, S3ObjectSummary tableConfigObject) {
        TableProperties tableProperties = new TableProperties(instanceProperties);
        try (InputStream in = s3.getObject(
                        tableConfigObject.getBucketName(),
                        tableConfigObject.getKey())
                .getObjectContent()) {
            tableProperties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tableProperties;
    }
}
