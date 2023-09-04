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

package sleeper.clients.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EcrRepositoriesInMemory implements EcrRepositoryCreator.Client {
    private final Set<String> repositories = new HashSet<>();
    private final Set<String> repositoriesWithEmrServerlessPolicy = new HashSet<>();

    public EcrRepositoriesInMemory(String... repositories) {
        this.repositories.addAll(List.of(repositories));
    }

    @Override
    public boolean repositoryExists(String repository) {
        return repositories.contains(repository);
    }

    @Override
    public void createRepository(String repository) {
        if (repositoryExists(repository)) {
            throw new IllegalArgumentException("Repository already exists: " + repository);
        }
        repositories.add(repository);
    }

    @Override
    public void deleteRepository(String repository) {
        repositories.remove(repository);
    }

    @Override
    public void createEmrServerlessAccessPolicy(String repository) {
        if (repositoriesWithEmrServerlessPolicy.contains(repository)) {
            throw new IllegalArgumentException("Repository already has EMR Serverless policy: " + repository);
        }
        repositoriesWithEmrServerlessPolicy.add(repository);
    }

    public Collection<String> getRepositories() {
        return repositories;
    }

    public Collection<String> getRepositoriesWithEmrServerlessPolicy() {
        return repositoriesWithEmrServerlessPolicy;
    }
}
