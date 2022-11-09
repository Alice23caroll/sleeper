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
package sleeper.build.status;

import sleeper.build.chunks.ProjectConfiguration;
import sleeper.build.maven.MavenModuleStructure;

import java.io.IOException;
import java.nio.file.Paths;

public class CheckGitHubStatusMain {

    private CheckGitHubStatusMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: <github.properties path> <chunks.yaml path> <Maven project base path>");
            System.exit(1);
            return;
        }
        ProjectConfiguration configuration = ProjectConfiguration.fromGitHubAndChunks(args[0], args[1]);
        MavenModuleStructure mavenProject = MavenModuleStructure.fromProjectBase(Paths.get(args[2]));
        configuration.getChunks().validateAllConfigured(mavenProject);
        ChunksStatus status = configuration.checkStatus();
        status.report(System.out);
        if (status.isFailCheck()) {
            System.exit(1);
        }
    }
}
