package sleeper.trino;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.*;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.statistics.ComputedStatistics;
import sleeper.core.key.Key;
import sleeper.core.partition.Partition;
import sleeper.core.range.Range;
import sleeper.trino.handle.SleeperColumnHandle;
import sleeper.trino.handle.SleeperInsertTableHandle;
import sleeper.trino.handle.SleeperPartitioningHandle;
import sleeper.trino.handle.SleeperTableHandle;
import sleeper.trino.remotesleeperconnection.SleeperConnectionAsTrino;

import javax.inject.Inject;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

/**
 * Provider of metadata about the table structure, and many other similar pieces of metadata, to the Trino framework.
 * This class also handles the application of static filters to tables, and the resolution of indexes.
 */
public class SleeperMetadata implements ConnectorMetadata {
    private static final Logger LOG = Logger.get(SleeperMetadata.class);

    private final SleeperConfig sleeperConfig;
    private final SleeperConnectionAsTrino sleeperConnectionAsTrino;

    @Inject
    public SleeperMetadata(SleeperConfig sleeperConfig,
                           SleeperConnectionAsTrino sleeperConnectionAsTrino) {
        this.sleeperConfig = requireNonNull(sleeperConfig);
        this.sleeperConnectionAsTrino = requireNonNull(sleeperConnectionAsTrino);
    }

    private static List<String> handleToNames(List<ColumnHandle> columnHandles) {
        return columnHandles.stream()
                .map(SleeperColumnHandle.class::cast)
                .map(SleeperColumnHandle::getColumnName)
                .collect(ImmutableList.toImmutableList());
    }

    /**
     * List all of the schemas in the underlying Sleeper database.
     *
     * @param session The current session. This makes no difference at present.
     * @return A list of all the names of the schemas.
     */
    @Override
    public List<String> listSchemaNames(ConnectorSession session) {
        return sleeperConnectionAsTrino.getAllTrinoSchemaNames();
    }

    /**
     * List all of the tables in a schema.
     *
     * @param session            The current session. This makes no difference at present.
     * @param trinoSchemaNameOpt The schema to examine, or the default schema if this is not set.
     * @return A list of {@link SchemaTableName} objects.
     */
    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> trinoSchemaNameOpt) {
        String trinoSchemaName = trinoSchemaNameOpt.orElse(this.sleeperConnectionAsTrino.getDefaultTrinoSchemaName());
        return this.sleeperConnectionAsTrino.getAllSchemaTableNamesInTrinoSchema(trinoSchemaName).stream()
                .sorted(Comparator.comparing(SchemaTableName::getSchemaName).thenComparing(SchemaTableName::getTableName))
                .collect(ImmutableList.toImmutableList());
    }

    /**
     * Retrieve a table handle for a specified schema and table name.
     *
     * @param session         The current session. This makes no difference at present.
     * @param schemaTableName The schema and table name.
     * @return The handle for the table.
     */
    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName schemaTableName) {
        return this.sleeperConnectionAsTrino.getSleeperTableHandle(schemaTableName);
    }

    /**
     * Retrieve the metadata for the specified table.
     *
     * @param session              The current session. This makes no difference at present.
     * @param connectorTableHandle The handle for the table to be examined.
     * @return The metadata for the table.
     */
    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle connectorTableHandle) {
        SleeperTableHandle sleeperTableHandle = (SleeperTableHandle) connectorTableHandle;
        return sleeperTableHandle.toConnectorTableMetadata();
    }

    /**
     * Retrieve {@link TableColumnsMetadata} objects for all of the tables which match a supplied prefix of schema name
     * and table name. This is a strange method which seems to have something to do with redirected tables.
     *
     * @param session The current session. This makes no difference at present.
     * @param prefix  The prefix to use to filter the tables.
     * @return A stream of {@link TableColumnsMetadata} objects.
     */
    @Override
    public Stream<TableColumnsMetadata> streamTableColumns(ConnectorSession session, SchemaTablePrefix prefix) {
        return this.listTables(session, prefix.getSchema()).stream()
                .filter(prefix::matches)
                .map(schemaTableName -> (SleeperTableHandle) this.getTableHandle(session, schemaTableName))
                .map(SleeperTableHandle::toTableColumnsMetadata);
    }

    /**
     * Retrieve the column handles for a specified table.
     *
     * @param session              The current session. This makes no difference at present.
     * @param connectorTableHandle The table to examine.
     * @return A map where the keys are the column names and the values are the corresponding column handles.
     */
    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle connectorTableHandle) {
        SleeperTableHandle sleeperTableHandle = (SleeperTableHandle) connectorTableHandle;
        return sleeperTableHandle.getSleeperColumnHandleListInOrder().stream()
                .collect(ImmutableMap.toImmutableMap(SleeperColumnHandle::getColumnName, Function.identity()));
    }

    /**
     * Retrieve the metadata for the specified column of the specified table.
     *
     * @param session               The current session. This makes no difference at present.
     * @param connectorTableHandle  The table.
     * @param connectorColumnHandle The column.
     * @return The metadata for the specified table and column.
     */
    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session,
                                            ConnectorTableHandle connectorTableHandle,
                                            ColumnHandle connectorColumnHandle) {
        SleeperColumnHandle sleeperColumnHandle = (SleeperColumnHandle) connectorColumnHandle;
        return sleeperColumnHandle.toColumnMetadata();
    }

    /**
     * Retrive the properties of the specified table. The properties that are currently returned include the {@link
     * SleeperPartitioningHandle} which describes how the table is partitioned, and a list of {@link SortingProperty}
     * objects which describe the key-0based sort order.
     * <p>
     * At the moment, every Sleeper partition translates into its own Trino partition, but if this becomes too many
     * partitions to pass around, it should be possible to combine consecutive Sleeper partitions into a single Trino
     * partition. The {@link ConnectorTableProperties} class has a range of other features which are not used here, such
     * as a predicate which applies to the whole table.
     * <p>
     * The configuration parameter {@link SleeperConfig#isEnableTrinoPartitioning()} can be used to turn off
     * partitioning.
     *
     * @param session     The current session. This makes no difference at present.
     * @param tableHandle The table to examine.
     * @return The {@link ConnectorTableProperties} object.
     */
    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle tableHandle) {
        if (this.sleeperConfig.isEnableTrinoPartitioning()) {
            SleeperTableHandle sleeperTableHandle = (SleeperTableHandle) tableHandle;
            List<ColumnHandle> keyColumnHandles = sleeperTableHandle.getColumnHandlesInCategoryInOrder(SleeperColumnHandle.SleeperColumnCategory.ROWKEY).stream()
                    .map(ColumnHandle.class::cast)
                    .collect(ImmutableList.toImmutableList());
            // Generate as list of the lower-bounds of eadh partition and use these to create a SleeperPartitioningHandle object.
            List<Key> partitionMinKeys = this.sleeperConnectionAsTrino.streamPartitions(sleeperTableHandle.getSchemaTableName())
                    .filter(Partition::isLeafPartition)
                    .map(partition -> Key.create(partition.getRegion().getRanges().stream().map(Range::getMin).collect(ImmutableList.toImmutableList())))
                    .collect(ImmutableList.toImmutableList());
            ConnectorTablePartitioning connectorTablePartitioning = new ConnectorTablePartitioning(
                    new SleeperPartitioningHandle(sleeperTableHandle, partitionMinKeys), keyColumnHandles);
            // The local properties reflect that Sleeper stores its keys in sort-order. There are no nulls in Sleeper,
            // but Trino only has an ASC_NULLS_FIRST (or last) option.
            List<LocalProperty<ColumnHandle>> localProperties = keyColumnHandles.stream()
                    .map(columnHandle -> new SortingProperty<>(columnHandle, SortOrder.ASC_NULLS_FIRST))
                    .collect(ImmutableList.toImmutableList());
            return new ConnectorTableProperties(
                    TupleDomain.all(),
                    Optional.of(connectorTablePartitioning),
                    Optional.empty(),
                    Optional.empty(),
                    localProperties);
        } else {
            return new ConnectorTableProperties();
        }
    }

    /**
     * Apply a static filter to a table.
     * <p>
     * This method is the mechanism where predicates can be pushed down by the Trino framework into an underlying
     * connector. The method is called repeatedly during the query-optimisation phase and so some of the filters that
     * are passed may never make it into the final execution.
     * <p>
     * This method is passed a {@link SleeperTableHandle} which contains the {@link TupleDomain} that is currently
     * applied to the table, plus an additional {@link Constraint} to try to apply to the table. If this method can push
     * down the supplied constraint then it returns a {@link ConstraintApplicationResult} object specifying the new,
     * filtered, table handle and which parts of the constraint have not been pushed down.
     * <p>
     * This implementation only considers the {@link TupleDomain} part of any {@link Constraint}. The only filters that
     * are pushed down are those which apply to row keys.
     *
     * @param session              The current session. This makes no difference at present.
     * @param connectorTableHandle The table to apply the filter to.
     * @param additionalConstraint The additional constraint to try to apply to the table.
     * @return If any part of the constraint can be pushed down, then a {@link ConstraintApplicationResult} is returned
     * with the new table handle and the remaining parts of the constraint which have not been applied. An empty result
     * indicates that this filter could not be pushed down.
     */
    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session,
                                                                                   ConnectorTableHandle connectorTableHandle,
                                                                                   Constraint additionalConstraint) {
        SleeperTableHandle sleeperTableHandle = (SleeperTableHandle) connectorTableHandle;
        LOG.debug("applyFilter on %s: %s", sleeperTableHandle.getSchemaTableName(), additionalConstraint.getSummary().getDomains());

        Set<SleeperColumnHandle> rowKeyColumnHandlesSet = ImmutableSet.copyOf(
                sleeperTableHandle.getColumnHandlesInCategoryInOrder(SleeperColumnHandle.SleeperColumnCategory.ROWKEY));

        Optional<Map<ColumnHandle, Domain>> additionalConstraintColumnHandleToDomainMapOpt =
                additionalConstraint.getSummary().getDomains();
        if (additionalConstraintColumnHandleToDomainMapOpt.isEmpty()) {
            LOG.debug("No domains were provided in the constraint");
            return Optional.empty();
        }
        Map<ColumnHandle, Domain> additionalConstraintColumnHandleToDomainMap =
                additionalConstraintColumnHandleToDomainMapOpt.get();

        Map<ColumnHandle, Domain> rowKeyConstraintsColumnHandleToDomainMap =
                additionalConstraintColumnHandleToDomainMap.entrySet().stream()
                        .filter(entry -> rowKeyColumnHandlesSet.contains((SleeperColumnHandle) entry.getKey()))
                        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (rowKeyConstraintsColumnHandleToDomainMap.isEmpty()) {
            LOG.debug("No row key domains were provided in the constraint");
            return Optional.empty();
        }
        TupleDomain<ColumnHandle> rowKeyConstraintsTupleDomain = TupleDomain.withColumnDomains(rowKeyConstraintsColumnHandleToDomainMap);

        Map<ColumnHandle, Domain> remainingConstraintsColumnHandleToDomainMap =
                additionalConstraintColumnHandleToDomainMap.entrySet().stream()
                        .filter(entry -> !rowKeyColumnHandlesSet.contains((SleeperColumnHandle) entry.getKey()))
                        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        TupleDomain<ColumnHandle> remainingConstraintsTupleDomain = TupleDomain.withColumnDomains(remainingConstraintsColumnHandleToDomainMap);

        TupleDomain<ColumnHandle> originalTableTupleDomain = sleeperTableHandle.getTupleDomain();
        TupleDomain<ColumnHandle> rowKeyConstrainedTableTupleDomain = originalTableTupleDomain.intersect(rowKeyConstraintsTupleDomain);
        if (originalTableTupleDomain.equals(rowKeyConstrainedTableTupleDomain)) {
            LOG.debug("New row key domains did not change the overall tuple domain");
            return Optional.empty();
        }

        LOG.debug("New domain is %s", rowKeyConstrainedTableTupleDomain);
        LOG.debug("Remaining domain is %s", remainingConstraintsTupleDomain);
        return Optional.of(new ConstraintApplicationResult<>(sleeperTableHandle.withTupleDomain(rowKeyConstrainedTableTupleDomain),
                remainingConstraintsTupleDomain,
                false));
    }

    /**
     * Begin an INSERT statement to add rows to a table.
     *
     * @param session     The session to perform this action under.
     * @param tableHandle The table to add the rows to.
     * @param columns     The columns which are to be added. Null or default columns are not permitted and so these
     *                    columns must match the columns in the table.
     * @param retryMode   The retry mode (only NO_RETRIES is supported)
     * @return A handle to this insert operation.
     */
    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session,
                                                  ConnectorTableHandle tableHandle,
                                                  List<ColumnHandle> columns,
                                                  RetryMode retryMode) {
        if (retryMode != RetryMode.NO_RETRIES) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support query retries");
        }
        List<SleeperColumnHandle> sleeperColumnHandlesInOrder = columns.stream()
                .map(SleeperColumnHandle.class::cast)
                .collect(ImmutableList.toImmutableList());
        return new SleeperInsertTableHandle((SleeperTableHandle) tableHandle, sleeperColumnHandlesInOrder);
    }

    /**
     * Finish the INSERT operation.
     * <p>
     * In this implementation, the {@link SleeperPageSink} does all of the work and this method does nothing.
     *
     * @param session            The session to perform this action under.
     * @param insertHandle       The handle of the insert operation.
     * @param fragments          The values which are returned by {@link SleeperPageSink#finish()}, once the future has
     *                           resolved. In this implementation, no values are passed back from the {@link
     *                           SleeperPageSink} to this metadata object.
     * @param computedStatistics Ignored.
     * @return An empty Optional.
     */
    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics) {
        // Do nothing - the records are written when the PageSink is closed
        return Optional.empty();
    }

    @Override
    public ConnectorTableHandle makeCompatiblePartitioning(ConnectorSession session, ConnectorTableHandle tableHandle, ConnectorPartitioningHandle partitioningHandle) {
        return tableHandle;
    }
}

