/*
 * Copyright 2022 Crown Copyright
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
package sleeper.build.chunks;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

public class GetChunkConfig {

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: <chunk ID> <property name> <chunks.yaml path>");
            System.out.println("Available properties (case insensitive): " + Arrays.toString(Property.values()));
            System.exit(1);
        }

        ProjectChunk chunk = ProjectChunks.fromYamlPath(args[2]).getById(args[0]);
        System.out.println(get(chunk, args[1]));
    }

    public static String get(ProjectChunk chunk, String property) {
        return Property.valueOf(property.toUpperCase(Locale.ROOT))
                .getter.apply(chunk);
    }

    private enum Property {
        NAME(ProjectChunk::getName), MAVEN_PROJECT_LIST(ProjectChunk::getMavenProjectList);

        private final Function<ProjectChunk, String> getter;

        Property(Function<ProjectChunk, String> getter) {
            this.getter = getter;
        }
    }
}
