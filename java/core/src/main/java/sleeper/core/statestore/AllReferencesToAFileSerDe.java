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
package sleeper.core.statestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class AllReferencesToAFileSerDe {

    private AllReferencesToAFileSerDe() {
    }

    public static SerDe noUpdateTimes() {
        return new FileSerDe();
    }

    public interface SerDe extends JsonSerializer<AllReferencesToAFile>, JsonDeserializer<AllReferencesToAFile> {
    }

    public static class FileSerDe implements SerDe {

        @Override
        public AllReferencesToAFile deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
            JsonObject object = json.getAsJsonObject();
            String filename = object.get("filename").getAsString();
            List<FileReference> references = new ArrayList<>();
            JsonArray referencesArr = object.get("references").getAsJsonArray();
            for (JsonElement referenceElem : referencesArr) {
                JsonObject referenceObj = referenceElem.getAsJsonObject();
                referenceObj.addProperty("filename", filename);
                references.add(context.deserialize(referenceObj, FileReference.class));
            }
            return AllReferencesToAFile.builder()
                    .filename(filename)
                    .totalReferenceCount(object.get("totalReferenceCount").getAsInt())
                    .internalReferences(references)
                    .build();
        }

        @Override
        public JsonElement serialize(AllReferencesToAFile file, Type type, JsonSerializationContext context) {
            JsonObject object = new JsonObject();
            object.addProperty("filename", file.getFilename());
            object.addProperty("totalReferenceCount", file.getTotalReferenceCount());
            JsonArray referencesArr = new JsonArray();
            for (FileReference reference : file.getInternalReferences()) {
                JsonObject referenceObj = context.serialize(reference).getAsJsonObject();
                referenceObj.remove("filename");
                referencesArr.add(referenceObj);
            }
            object.add("references", referencesArr);
            return object;
        }
    }
}
