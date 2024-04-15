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
package sleeper.ingest.job;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines a job that will ingest data from one or more files to a Sleeper table.
 */
public class IngestJob {
    private final String id;
    private final String tableName;
    private final String tableId;
    private final List<String> files;

    private IngestJob(Builder builder) {
        id = builder.id;
        tableName = builder.tableName;
        tableId = builder.tableId;
        files = builder.files;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTableId() {
        return tableId;
    }

    public List<String> getFiles() {
        return files;
    }

    public int getFileCount() {
        return Optional.ofNullable(files).map(List::size).orElse(0);
    }

    public Builder toBuilder() {
        return builder().id(id).files(files).tableName(tableName);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        IngestJob ingestJob = (IngestJob) object;
        return Objects.equals(id, ingestJob.id) && Objects.equals(tableName, ingestJob.tableName)
                && Objects.equals(tableId, ingestJob.tableId) && Objects.equals(files, ingestJob.files);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tableName, tableId, files);
    }

    @Override
    public String toString() {
        return "IngestJob{" +
                "id='" + id + '\'' +
                ", tableName='" + tableName + '\'' +
                ", tableId='" + tableId + '\'' +
                ", files=" + files +
                '}';
    }

    /**
     * Builder for creating ingest job objects.
     */
    public static final class Builder {
        private String id;
        private String tableName;
        private String tableId;
        private List<String> files;

        private Builder() {
        }

        /**
         * Sets the ingest job ID.
         *
         * @param  id the ingest job ID
         * @return    the builder
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the table name.
         *
         * @param  tableName the table name
         * @return           the builder
         */
        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /**
         * Sets the table ID.
         *
         * @param  tableId the table ID
         * @return         the builder
         */
        public Builder tableId(String tableId) {
            this.tableId = tableId;
            return this;
        }

        /**
         * Sets the list of input files.
         *
         * @param  files the list of input files
         * @return       the builder
         */
        public Builder files(List<String> files) {
            this.files = files;
            return this;
        }

        /**
         * Sets the input files.
         *
         * @param  files the input files
         * @return       the builder
         */
        public Builder files(String... files) {
            return files(Arrays.asList(files));
        }

        public IngestJob build() {
            return new IngestJob(this);
        }
    }
}
