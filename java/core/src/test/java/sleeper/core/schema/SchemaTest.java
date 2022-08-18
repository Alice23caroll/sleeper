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
package sleeper.core.schema;

import org.junit.Test;
import sleeper.core.schema.type.ByteArrayType;
import sleeper.core.schema.type.IntType;
import sleeper.core.schema.type.ListType;
import sleeper.core.schema.type.MapType;
import sleeper.core.schema.type.StringType;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SchemaTest {

    @Test
    public void equalsShouldReturnCorrectResults() {
        // Given
        Schema schema1 = new Schema();
        schema1.setRowKeyFields(new Field("column1", new IntType()), new Field("column2", new StringType()));
        schema1.setSortKeyFields(new Field("column3", new IntType()));
        schema1.setValueFields(new Field("column4", new MapType(new IntType(), new ByteArrayType())),
                new Field("column5", new ListType(new StringType())));
        Schema schema2 = new Schema();
        schema2.setRowKeyFields(new Field("column1", new IntType()), new Field("column2", new StringType()));
        schema2.setSortKeyFields(new Field("column3", new IntType()));
        schema2.setValueFields(new Field("column4", new MapType(new IntType(), new ByteArrayType())),
                new Field("column5", new ListType(new StringType())));
        Schema schema3 = new Schema();
        schema3.setRowKeyFields(new Field("column1", new IntType()), new Field("column2", new StringType()));
        schema3.setSortKeyFields(new Field("column3", new IntType()));
        schema3.setValueFields(new Field("column4", new MapType(new IntType(), new StringType())));

        // When
        boolean test1 = schema1.equals(schema2);
        boolean test2 = schema1.equals(schema3);

        // Then
        assertThat(test1).isTrue();
        assertThat(test2).isFalse();
    }

    @Test
    public void shouldntAllowMapTypeAsRowKey() {
        // Given
        Field allowField = new Field("column1", new IntType());
        Field refuseField = new Field("column2", new MapType(new IntType(), new ByteArrayType()));
        Schema.Builder builder = Schema.builder().rowKeyFields(allowField, refuseField);
        Schema schema = new Schema();

        // When / Then
        assertThatThrownBy(() -> schema.setRowKeyFields(allowField, refuseField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Row key", "type");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Row key", "type");
    }

    @Test
    public void shouldntAllowMapTypeAsSortKey() {
        // Given
        List<Field> rowKeyFields = Arrays.asList(
                new Field("column1", new IntType()),
                new Field("column2", new StringType()));
        Field refuseField = new Field("column3", new MapType(new IntType(), new ByteArrayType()));
        Schema.Builder builder = Schema.builder().rowKeyFields(rowKeyFields).sortKeyFields(refuseField);
        Schema schema = new Schema();
        schema.setRowKeyFields(rowKeyFields);

        // When / Then
        assertThatThrownBy(() -> schema.setSortKeyFields(refuseField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Sort key", "type");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Sort key", "type");
    }

    @Test
    public void shouldntAllowListTypeAsRowKey() {
        // Given
        Field allowField = new Field("column1", new IntType());
        Field refuseField = new Field("column2", new ListType(new IntType()));
        Schema.Builder builder = Schema.builder().rowKeyFields(allowField, refuseField);
        Schema schema = new Schema();

        // When / Then
        assertThatThrownBy(() -> schema.setRowKeyFields(allowField, refuseField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Row key", "type");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Row key", "type");
    }

    @Test
    public void shouldntAllowListTypeAsSortKey() {
        // Given
        List<Field> rowKeyFields = Arrays.asList(
                new Field("column1", new IntType()),
                new Field("column2", new StringType()));
        Field refuseField = new Field("column3", new ListType(new IntType()));
        Schema.Builder builder = Schema.builder().rowKeyFields(rowKeyFields).sortKeyFields(refuseField);
        Schema schema = new Schema();
        schema.setRowKeyFields(rowKeyFields);

        // When / Then
        assertThatThrownBy(() -> schema.setSortKeyFields(refuseField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Sort key", "type");
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Sort key", "type");
    }
}
