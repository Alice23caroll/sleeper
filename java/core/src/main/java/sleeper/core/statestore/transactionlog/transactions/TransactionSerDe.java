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
package sleeper.core.statestore.transactionlog.transactions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import sleeper.core.partition.Partition;
import sleeper.core.partition.PartitionSerDe.PartitionJsonSerDe;
import sleeper.core.schema.Schema;
import sleeper.core.statestore.AllReferencesToAFile;
import sleeper.core.statestore.AllReferencesToAFileSerDe;
import sleeper.core.statestore.FileReferenceSerDe;
import sleeper.core.statestore.transactionlog.StateStoreTransaction;
import sleeper.core.util.GsonConfig;

public class TransactionSerDe {
    private final Gson gson;
    private final Gson gsonPrettyPrint;

    public TransactionSerDe(Schema schema) {
        GsonBuilder builder = GsonConfig.standardBuilder()
                .registerTypeAdapter(Partition.class, new PartitionJsonSerDe(schema))
                .registerTypeAdapter(AllReferencesToAFile.class, AllReferencesToAFileSerDe.noUpdateTimes())
                .addSerializationExclusionStrategy(FileReferenceSerDe.excludeUpdateTimes())
                .serializeNulls();
        gson = builder.create();
        gsonPrettyPrint = builder.setPrettyPrinting().create();
    }

    public String toJson(StateStoreTransaction<?> transaction) {
        return gson.toJson(transaction);
    }

    public String toJsonPrettyPrint(StateStoreTransaction<?> transaction) {
        return gsonPrettyPrint.toJson(transaction);
    }

    public StateStoreTransaction<?> toTransaction(TransactionType type, String json) {
        return gson.fromJson(json, type.getType());
    }
}
