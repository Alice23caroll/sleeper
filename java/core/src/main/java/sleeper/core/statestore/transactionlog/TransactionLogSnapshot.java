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
package sleeper.core.statestore.transactionlog;

import sleeper.core.schema.Field;
import sleeper.core.schema.Schema;
import sleeper.core.schema.type.IntType;
import sleeper.core.schema.type.ListType;
import sleeper.core.schema.type.StringType;
import sleeper.core.statestore.StateStoreException;

import java.nio.file.Path;

public interface TransactionLogSnapshot<T> {
    static Schema initialisePartitionSchema() {
        return Schema.builder()
                .rowKeyFields(new Field("partitionId", new StringType()))
                .valueFields(
                        new Field("leafPartition", new StringType()),
                        new Field("parentPartitionId", new StringType()),
                        new Field("childPartitionIds", new ListType(new StringType())),
                        new Field("region", new StringType()),
                        new Field("dimension", new IntType()))
                .build();
    }

    T state() throws StateStoreException;

    long lastTransactionNumber() throws StateStoreException;

    void save(Path path) throws StateStoreException;
}
