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
package sleeper.compaction.jobexecution;

import com.facebook.collections.ByteArray;
import org.apache.hadoop.fs.Path;
import sleeper.core.record.Record;
import sleeper.core.schema.Schema;
import sleeper.io.parquet.record.ParquetReaderIterator;
import sleeper.io.parquet.record.ParquetRecordReader;
import sleeper.io.parquet.record.ParquetRecordWriter;
import sleeper.io.parquet.record.SchemaConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class CompactSortedFilesTestData {

    private CompactSortedFilesTestData() {
    }

    public static List<Record> keyAndTwoValuesSortedEvenLongs() {
        return streamKeyAndTwoValuesFromEvens(n -> (long) n)
                .collect(Collectors.toList());
    }

    public static List<Record> keyAndTwoValuesSortedOddLongs() {
        return streamKeyAndTwoValuesFromOdds(n -> (long) n)
                .collect(Collectors.toList());
    }

    public static List<Record> keyAndTwoValuesSortedEvenStrings() {
        return streamKeyAndTwoValuesFromEvens(n -> "" + n)
                .sorted(comparingStringKey())
                .collect(Collectors.toList());
    }

    public static List<Record> keyAndTwoValuesSortedOddStrings() {
        return streamKeyAndTwoValuesFromOdds(n -> "" + n)
                .sorted(comparingStringKey())
                .collect(Collectors.toList());
    }

    public static List<Record> keyAndTwoValuesSortedEvenByteArrays() {
        return streamKeyAndTwoValuesFromEvens(CompactSortedFilesTestData::nthByteArray)
                .collect(Collectors.toList());
    }

    public static List<Record> keyAndTwoValuesSortedOddByteArrays() {
        return streamKeyAndTwoValuesFromOdds(CompactSortedFilesTestData::nthByteArray)
                .collect(Collectors.toList());
    }

    private static byte[] nthByteArray(int n) {
        return new byte[]{
                (byte) (n / 128),
                (byte) (n % 128)
        };
    }

    private static Stream<Record> streamKeyAndTwoValuesFromEvens(Function<Integer, Object> convert) {
        return IntStream.range(0, 100)
                .mapToObj(i -> {
                    int even = 2 * i;
                    Object converted = convert.apply(even);
                    Record record = new Record();
                    record.put("key", converted);
                    record.put("value1", converted);
                    record.put("value2", 987654321L);
                    return record;
                });
    }

    private static Stream<Record> streamKeyAndTwoValuesFromOdds(Function<Integer, Object> convert) {
        Object value1 = convert.apply(1001);
        return IntStream.range(0, 100)
                .mapToObj(i -> {
                    int odd = 2 * i + 1;
                    Object converted = convert.apply(odd);
                    Record record = new Record();
                    record.put("key", converted);
                    record.put("value1", value1);
                    record.put("value2", 123456789L);
                    return record;
                });
    }

    private static Comparator<Record> comparingStringKey() {
        return Comparator.comparing(r -> (String) r.get("key"));
    }

    private static Comparator<Record> comparingByteArrayKey() {
        return Comparator.comparing(r -> ByteArray.wrap((byte[]) r.get("key")));
    }

    public static List<Record> combineSortedBySingleKey(List<Record> data1, List<Record> data2) {
        return combineSortedBySingleKey(data1, data2, record -> record.get("key"));
    }

    public static List<Record> combineSortedBySingleByteArrayKey(List<Record> data1, List<Record> data2) {
        return combineSortedBySingleKey(data1, data2, record -> ByteArray.wrap((byte[]) record.get("key")));
    }

    private static List<Record> combineSortedBySingleKey(List<Record> data1, List<Record> data2, Function<Record, Object> getKey) {
        SortedMap<Object, Record> data = new TreeMap<>();
        data1.forEach(record -> data.put(getKey.apply(record), record));
        data2.forEach(record -> data.put(getKey.apply(record), record));
        return new ArrayList<>(data.values());
    }

    public static void writeDataFile(Schema schema, String filename, List<Record> records) throws IOException {
        try (ParquetRecordWriter writer = new ParquetRecordWriter(new Path(filename), SchemaConverter.getSchema(schema), schema)) {
            for (Record record : records) {
                writer.write(record);
            }
        }
    }

    public static List<Record> readDataFile(Schema schema, String filename) throws IOException {
        List<Record> results = new ArrayList<>();
        try (ParquetReaderIterator reader = new ParquetReaderIterator(new ParquetRecordReader(new Path(filename), schema))) {
            while (reader.hasNext()) {
                results.add(new Record(reader.next()));
            }
        }
        return results;
    }
}
