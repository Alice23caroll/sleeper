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
package sleeper.core.statestore.inmemory;

import sleeper.core.statestore.AllFileReferences;
import sleeper.core.statestore.FileInfo;
import sleeper.core.statestore.FileInfoStore;
import sleeper.core.statestore.FileReferenceCount;
import sleeper.core.statestore.StateStoreException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;

public class InMemoryFileInfoStore implements FileInfoStore {

    private final Map<String, PartitionFiles> partitionById = new LinkedHashMap<>();
    private final Map<String, FileReferenceCount> referenceCountByFilename = new LinkedHashMap<>();
    private Clock clock = Clock.systemUTC();

    private class PartitionFiles {
        private final Map<String, FileInfo> activeFiles = new LinkedHashMap<>();

        void add(FileInfo fileInfo) throws StateStoreException {
            if (activeFiles.containsKey(fileInfo.getFilename())) {
                throw new StateStoreException("File already exists for partition: " + fileInfo);
            }
            activeFiles.put(fileInfo.getFilename(), fileInfo.toBuilder().lastStateStoreUpdateTime(clock.millis()).build());
            incrementReferences(fileInfo);
        }

        void moveToGC(String filename) throws StateStoreException {
            if (!activeFiles.containsKey(filename)) {
                throw new StateStoreException("Cannot move to ready for GC as file is not active: " + filename);
            }
            activeFiles.remove(filename);
            decrementReferences(filename);
        }

        boolean isEmpty() {
            return activeFiles.isEmpty();
        }
    }

    @Override
    public void addFile(FileInfo fileInfo) throws StateStoreException {
        partitionById.computeIfAbsent(fileInfo.getPartitionId(), partitionId -> new PartitionFiles())
                .add(fileInfo);
    }

    @Override
    public void addFiles(List<FileInfo> fileInfos) throws StateStoreException {
        for (FileInfo fileInfo : fileInfos) {
            addFile(fileInfo);
        }
    }

    @Override
    public List<FileInfo> getActiveFiles() {
        return activeFiles().collect(toUnmodifiableList());
    }

    @Override
    public Stream<String> getReadyForGCFilenamesBefore(Instant maxUpdateTime) {
        List<FileReferenceCount> counts = new ArrayList<>(referenceCountByFilename.values());
        return counts.stream()
                .filter(file -> file.getReferences() < 1)
                .filter(file -> file.getLastUpdateTime().isBefore(maxUpdateTime))
                .map(FileReferenceCount::getFilename);
    }

    @Override
    public List<FileInfo> getActiveFilesWithNoJobId() {
        return activeFiles()
                .filter(file -> file.getJobId() == null)
                .collect(toUnmodifiableList());
    }

    @Override
    public Map<String, List<String>> getPartitionToActiveFilesMap() {
        return activeFiles().collect(
                groupingBy(FileInfo::getPartitionId,
                        mapping(FileInfo::getFilename, toList())));
    }

    public void atomicallyUpdateFilesToReadyForGCAndCreateNewActiveFiles(String partitionId, List<String> filesToBeMarkedReadyForGC, List<FileInfo> newFiles) throws StateStoreException {
        for (String file : filesToBeMarkedReadyForGC) {
            PartitionFiles partition = partitionById.get(partitionId);
            partition.moveToGC(file);
            if (partition.isEmpty()) {
                partitionById.remove(partitionId);
            }
        }
        addFiles(newFiles);
    }

    private Stream<FileInfo> activeFiles() {
        return partitionById.values().stream()
                .flatMap(partition -> partition.activeFiles.values().stream());
    }

    @Override
    public void atomicallyUpdateJobStatusOfFiles(String jobId, List<FileInfo> fileInfos) throws StateStoreException {
        List<FileInfo> updateFiles = new ArrayList<>();
        for (FileInfo requestedFile : fileInfos) {
            PartitionFiles partition = partitionById.get(requestedFile.getPartitionId());
            if (partition == null) {
                throw new StateStoreException("Partition contains no files: " + requestedFile.getPartitionId());
            }
            FileInfo file = partition.activeFiles.get(requestedFile.getFilename());
            if (file == null) {
                throw new StateStoreException("File not found in partition " + requestedFile.getPartitionId() + ": " + requestedFile.getFilename());
            }
            if (file.getJobId() != null) {
                throw new StateStoreException("Job ID already set: " + file);
            }
            updateFiles.add(file.toBuilder().jobId(jobId).lastStateStoreUpdateTime(clock.millis()).build());
        }
        for (FileInfo file : updateFiles) {
            partitionById.get(file.getPartitionId())
                    .activeFiles.put(file.getFilename(), file);
        }
    }

    @Override
    public void deleteReadyForGCFiles(List<String> filenames) throws StateStoreException {
        for (String filename : filenames) {
            FileReferenceCount count = referenceCountByFilename.get(filename);
            if (count == null || count.getReferences() > 0) {
                throw new StateStoreException("File is not ready for garbage collection: " + filename);
            }
        }
        filenames.forEach(referenceCountByFilename::remove);
    }

    @Override
    public AllFileReferences getAllFileReferencesWithMaxUnreferenced(int maxUnreferencedFiles) {
        List<FileReferenceCount> unreferencedCounts = referenceCountByFilename.values().stream()
                .filter(fileReferenceCount -> fileReferenceCount.getReferences() == 0)
                .collect(toUnmodifiableList());
        Set<FileInfo> activeFiles = partitionById.values().stream()
                .flatMap(files -> files.activeFiles.values().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unreferencedFiles = unreferencedCounts.stream()
                .map(FileReferenceCount::getFilename)
                .limit(maxUnreferencedFiles)
                .collect(Collectors.toCollection(TreeSet::new));
        return new AllFileReferences(activeFiles, unreferencedFiles,
                unreferencedCounts.size() > maxUnreferencedFiles);
    }

    @Override
    public void initialise() {

    }

    @Override
    public boolean hasNoFiles() {
        return partitionById.isEmpty() && referenceCountByFilename.isEmpty();
    }

    @Override
    public void clearFileData() {
        partitionById.clear();
    }

    @Override
    public void fixTime(Instant now) {
        clock = Clock.fixed(now, ZoneId.of("UTC"));
    }

    private void incrementReferences(FileInfo fileInfo) {
        updateReferenceCount(fileInfo.getFilename(), FileReferenceCount::increment);
    }

    private void decrementReferences(String filename) {
        updateReferenceCount(filename, FileReferenceCount::decrement);
    }

    private void updateReferenceCount(String filename, BiFunction<FileReferenceCount, Instant, FileReferenceCount> update) {
        FileReferenceCount before = referenceCountByFilename.get(filename);
        if (before == null) {
            before = FileReferenceCount.empty(filename);
        }
        referenceCountByFilename.put(filename, update.apply(before, clock.instant()));
    }
}
