/*
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
package io.trino.plugin.sqlserver;

import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcExpression;
import io.trino.plugin.jdbc.JdbcJoinCondition;
import io.trino.plugin.jdbc.JdbcOutputTableHandle;
import io.trino.plugin.jdbc.JdbcSortItem;
import io.trino.plugin.jdbc.JdbcSplit;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongReadFunction;
import io.trino.plugin.jdbc.LongWriteFunction;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.SliceWriteFunction;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.expression.AggregateFunctionRewriter;
import io.trino.plugin.jdbc.expression.AggregateFunctionRule;
import io.trino.plugin.jdbc.expression.ImplementAvgDecimal;
import io.trino.plugin.jdbc.expression.ImplementAvgFloatingPoint;
import io.trino.plugin.jdbc.expression.ImplementCount;
import io.trino.plugin.jdbc.expression.ImplementCountAll;
import io.trino.plugin.jdbc.expression.ImplementMinMax;
import io.trino.plugin.jdbc.expression.ImplementSum;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.JoinCondition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.microsoft.sqlserver.jdbc.SQLServerConnection.TRANSACTION_SNAPSHOT;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.defaultCharColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.defaultVarcharColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.fromTrinoTime;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.longTimestampWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.realColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.timeReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.plugin.sqlserver.SqlServerTableProperties.DATA_COMPRESSION;
import static io.trino.plugin.sqlserver.SqlServerTableProperties.getDataCompression;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.spi.type.TimestampType.MAX_SHORT_PRECISION;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_DAY;
import static io.trino.spi.type.Timestamps.round;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.math.RoundingMode.UNNECESSARY;
import static java.time.Duration.ofMinutes;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class SqlServerClient
        extends BaseJdbcClient
{
    // SqlServer supports 2100 parameters in prepared statement, let's create a space for about 4 big IN predicates
    public static final int SQL_SERVER_MAX_LIST_EXPRESSIONS = 500;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Joiner DOT_JOINER = Joiner.on(".");

    private final boolean snapshotIsolationDisabled;
    private final Cache<SnapshotIsolationEnabledCacheKey, Boolean> snapshotIsolationEnabled = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(ofMinutes(5))
            .build();

    private final AggregateFunctionRewriter aggregateFunctionRewriter;

    private static final int MAX_SUPPORTED_TEMPORAL_PRECISION = 7;

    @Inject
    public SqlServerClient(BaseJdbcConfig config, SqlServerConfig sqlServerConfig, ConnectionFactory connectionFactory)
    {
        super(config, "\"", connectionFactory);

        requireNonNull(sqlServerConfig, "sqlServerConfig is null");
        snapshotIsolationDisabled = sqlServerConfig.isSnapshotIsolationDisabled();

        JdbcTypeHandle bigintTypeHandle = new JdbcTypeHandle(Types.BIGINT, Optional.of("bigint"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        this.aggregateFunctionRewriter = new AggregateFunctionRewriter(
                this::quoted,
                ImmutableSet.<AggregateFunctionRule>builder()
                        .add(new ImplementCountAll(bigintTypeHandle))
                        .add(new ImplementCount(bigintTypeHandle))
                        .add(new ImplementMinMax())
                        .add(new ImplementSum(SqlServerClient::toTypeHandle))
                        .add(new ImplementAvgFloatingPoint())
                        .add(new ImplementAvgDecimal())
                        .add(new ImplementAvgBigint())
                        .add(new ImplementSqlServerStdev())
                        .add(new ImplementSqlServerStddevPop())
                        .add(new ImplementSqlServerVariance())
                        .add(new ImplementSqlServerVariancePop())
                        // SQL Server doesn't have covar_samp and covar_pop functions so we can't implement pushdown for them
                        .build());
    }

    @Override
    protected void renameTable(ConnectorSession session, String catalogName, String schemaName, String tableName, SchemaTableName newTable)
    {
        if (!schemaName.equals(newTable.getSchemaName())) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables across schemas");
        }

        String sql = format(
                "sp_rename %s, %s",
                singleQuote(catalogName, schemaName, tableName),
                singleQuote(newTable.getTableName()));
        execute(session, sql);
    }

    @Override
    public void renameColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        String sql = format(
                "sp_rename %s, %s, 'COLUMN'",
                singleQuote(handle.getCatalogName(), handle.getSchemaName(), handle.getTableName(), jdbcColumn.getColumnName()),
                singleQuote(newColumnName));
        execute(session, sql);
    }

    @Override
    protected void copyTableSchema(Connection connection, String catalogName, String schemaName, String tableName, String newTableName, List<String> columnNames)
    {
        String sql = format(
                "SELECT %s INTO %s FROM %s WHERE 0 = 1",
                columnNames.stream()
                        .map(this::quoted)
                        .collect(joining(", ")),
                quoted(catalogName, schemaName, newTableName),
                quoted(catalogName, schemaName, tableName));
        execute(connection, sql);
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }

        String jdbcTypeName = typeHandle.getJdbcTypeName()
                .orElseThrow(() -> new TrinoException(JDBC_ERROR, "Type name is missing: " + typeHandle));

        switch (jdbcTypeName) {
            case "varbinary":
                return Optional.of(varbinaryColumnMapping());
        }

        switch (typeHandle.getJdbcType()) {
            case Types.BIT:
                return Optional.of(booleanColumnMapping());

            case Types.TINYINT:
                return Optional.of(tinyintColumnMapping());

            case Types.SMALLINT:
                return Optional.of(smallintColumnMapping());

            case Types.INTEGER:
                return Optional.of(integerColumnMapping());

            case Types.BIGINT:
                return Optional.of(bigintColumnMapping());

            case Types.REAL:
                return Optional.of(realColumnMapping());

            case Types.DOUBLE:
                return Optional.of(doubleColumnMapping());

            case Types.NUMERIC:
            case Types.DECIMAL: {
                int columnSize = typeHandle.getRequiredColumnSize();
                int decimalDigits = typeHandle.getRequiredDecimalDigits();
                // TODO does sql server support negative scale?
                int precision = columnSize + max(-decimalDigits, 0); // Map decimal(p, -s) (negative scale) to decimal(p+s, 0).
                if (precision > Decimals.MAX_PRECISION) {
                    break;
                }
                return Optional.of(decimalColumnMapping(createDecimalType(precision, max(decimalDigits, 0)), UNNECESSARY));
            }

            case Types.CHAR:
            case Types.NCHAR:
                return Optional.of(defaultCharColumnMapping(typeHandle.getRequiredColumnSize(), false));

            case Types.VARCHAR:
            case Types.NVARCHAR:
                return Optional.of(defaultVarcharColumnMapping(typeHandle.getRequiredColumnSize(), false));

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return Optional.of(varbinaryColumnMapping());

            case Types.DATE:
                return Optional.of(ColumnMapping.longMapping(
                        DATE,
                        sqlServerDateReadFunction(),
                        sqlServerDateWriteFunction()));

            case Types.TIME:
                TimeType timeType = createTimeType(typeHandle.getRequiredDecimalDigits());
                return Optional.of(ColumnMapping.longMapping(
                        timeType,
                        timeReadFunction(timeType),
                        sqlServerTimeWriteFunction(timeType.getPrecision())));

            case Types.TIMESTAMP:
                int precision = typeHandle.getRequiredDecimalDigits();
                return Optional.of(timestampColumnMapping(createTimestampType(precision)));
        }

        // TODO (https://github.com/trinodb/trino/issues/4593) implement proper type mapping
        return legacyToPrestoType(session, connection, typeHandle);
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (type == BOOLEAN) {
            return WriteMapping.booleanMapping("bit", booleanWriteFunction());
        }

        if (type == BIGINT) {
            return WriteMapping.longMapping("bigint", bigintWriteFunction());
        }
        if (type == INTEGER) {
            return WriteMapping.longMapping("integer", integerWriteFunction());
        }
        if (type == SMALLINT) {
            return WriteMapping.longMapping("smallint", smallintWriteFunction());
        }
        if (type == TINYINT) {
            return WriteMapping.longMapping("tinyint", tinyintWriteFunction());
        }

        if (type == DOUBLE) {
            return WriteMapping.doubleMapping("double precision", doubleWriteFunction());
        }

        if (type instanceof VarcharType) {
            VarcharType varcharType = (VarcharType) type;
            String dataType;
            if (varcharType.isUnbounded() || varcharType.getBoundedLength() > 4000) {
                dataType = "nvarchar(max)";
            }
            else {
                dataType = "nvarchar(" + varcharType.getBoundedLength() + ")";
            }
            return WriteMapping.sliceMapping(dataType, varcharWriteFunction());
        }

        if (type instanceof CharType) {
            CharType charType = (CharType) type;
            String dataType;
            if (charType.getLength() > 4000) {
                dataType = "nvarchar(max)";
            }
            else {
                dataType = "nchar(" + charType.getLength() + ")";
            }
            return WriteMapping.sliceMapping(dataType, charWriteFunction());
        }

        if (type instanceof VarbinaryType) {
            return WriteMapping.sliceMapping("varbinary(max)", varbinaryWriteFunction());
        }

        if (type == DATE) {
            return WriteMapping.longMapping("date", sqlServerDateWriteFunction());
        }

        if (type instanceof TimeType) {
            TimeType timeType = (TimeType) type;
            int precision = min(timeType.getPrecision(), MAX_SUPPORTED_TEMPORAL_PRECISION);
            String dataType = format("time(%d)", precision);
            return WriteMapping.longMapping(dataType, sqlServerTimeWriteFunction(precision));
        }

        if (type instanceof TimestampType) {
            TimestampType timestampType = (TimestampType) type;
            String dataType = format("datetime2(%d)", min(timestampType.getPrecision(), MAX_SUPPORTED_TEMPORAL_PRECISION));
            if (timestampType.getPrecision() <= MAX_SHORT_PRECISION) {
                return WriteMapping.longMapping(dataType, timestampWriteFunction(timestampType));
            }
            return WriteMapping.objectMapping(dataType, longTimestampWriteFunction(timestampType));
        }

        // TODO implement proper type mapping
        return legacyToWriteMapping(session, type);
    }

    private LongWriteFunction sqlServerTimeWriteFunction(int precision)
    {
        return new LongWriteFunction()
        {
            @Override
            public String getBindExpression()
            {
                // Binding setObject(LocalTime) can result with "The data types time and datetime are incompatible in the equal to operator."
                // when write function is used for predicate pushdown.
                return format("CAST(? AS time(%s))", precision);
            }

            @Override
            public void set(PreparedStatement statement, int index, long picosOfDay)
                    throws SQLException
            {
                picosOfDay = round(picosOfDay, 12 - precision);
                if (picosOfDay == PICOSECONDS_PER_DAY) {
                    picosOfDay = 0;
                }
                LocalTime localTime = fromTrinoTime(picosOfDay);
                statement.setString(index, localTime.toString());
            }
        };
    }

    private static LongWriteFunction sqlServerDateWriteFunction()
    {
        return (statement, index, day) -> statement.setString(index, DATE_FORMATTER.format(LocalDate.ofEpochDay(day)));
    }

    private static LongReadFunction sqlServerDateReadFunction()
    {
        return (resultSet, index) -> LocalDate.parse(resultSet.getString(index), DATE_FORMATTER).toEpochDay();
    }

    @Override
    public Optional<JdbcExpression> implementAggregation(ConnectorSession session, AggregateFunction aggregate, Map<String, ColumnHandle> assignments)
    {
        // TODO support complex ConnectorExpressions
        return aggregateFunctionRewriter.rewrite(session, aggregate, assignments);
    }

    private static Optional<JdbcTypeHandle> toTypeHandle(DecimalType decimalType)
    {
        return Optional.of(new JdbcTypeHandle(Types.NUMERIC, Optional.of("decimal"), Optional.of(decimalType.getPrecision()), Optional.of(decimalType.getScale()), Optional.empty(), Optional.empty()));
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> format("SELECT TOP %s * FROM (%s) o", limit, sql));
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    public boolean supportsTopN(ConnectorSession session, JdbcTableHandle handle, List<JdbcSortItem> sortOrder)
    {
        for (JdbcSortItem sortItem : sortOrder) {
            Type sortItemType = sortItem.getColumn().getColumnType();
            if (sortItemType instanceof CharType || sortItemType instanceof VarcharType) {
                // Remote database can be case insensitive.
                return false;
            }
        }
        return true;
    }

    @Override
    protected Optional<TopNFunction> topNFunction()
    {
        return Optional.of((query, sortItems, limit) -> {
            String orderBy = sortItems.stream()
                    .flatMap(sortItem -> {
                        String ordering = sortItem.getSortOrder().isAscending() ? "ASC" : "DESC";
                        String columnSorting = format("%s %s", quoted(sortItem.getColumn().getColumnName()), ordering);

                        switch (sortItem.getSortOrder()) {
                            case ASC_NULLS_FIRST:
                                // In SQL Server ASC implies NULLS FIRST
                            case DESC_NULLS_LAST:
                                // In SQL Server DESC implies NULLS LAST
                                return Stream.of(columnSorting);

                            case ASC_NULLS_LAST:
                                return Stream.of(
                                        format("(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) ASC", quoted(sortItem.getColumn().getColumnName())),
                                        columnSorting);
                            case DESC_NULLS_FIRST:
                                return Stream.of(
                                        format("(CASE WHEN %s IS NULL THEN 1 ELSE 0 END) DESC", quoted(sortItem.getColumn().getColumnName())),
                                        columnSorting);
                        }
                        throw new UnsupportedOperationException("Unsupported sort order: " + sortItem.getSortOrder());
                    })
                    .collect(joining(", "));
            return format("%s ORDER BY %s OFFSET 0 ROWS FETCH NEXT %s ROWS ONLY", query, orderBy, limit);
        });
    }

    @Override
    public boolean isTopNLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    @Override
    protected boolean isSupportedJoinCondition(JdbcJoinCondition joinCondition)
    {
        if (joinCondition.getOperator() == JoinCondition.Operator.IS_DISTINCT_FROM) {
            // Not supported in SQL Server
            return false;
        }

        // Remote database can be case insensitive.
        return Stream.of(joinCondition.getLeftColumn(), joinCondition.getRightColumn())
                .map(JdbcColumnHandle::getColumnType)
                .noneMatch(type -> type instanceof CharType || type instanceof VarcharType);
    }

    @Override
    protected String createTableSql(RemoteTableName remoteTableName, List<String> columns, ConnectorTableMetadata tableMetadata)
    {
        return format(
                "CREATE TABLE %s (%s) %s",
                quoted(remoteTableName),
                join(", ", columns),
                getDataCompression(tableMetadata.getProperties())
                        .map(dataCompression -> format("WITH (DATA_COMPRESSION = %s)", dataCompression))
                        .orElse(""));
    }

    @Override
    public Map<String, Object> getTableProperties(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        if (!tableHandle.isNamedRelation()) {
            return ImmutableMap.of();
        }
        try (Connection connection = configureConnectionTransactionIsolation(connectionFactory.openConnection(session));
                Handle handle = Jdbi.open(connection)) {
            return getTableDataCompression(handle, tableHandle)
                    .map(dataCompression -> ImmutableMap.<String, Object>of(DATA_COMPRESSION, dataCompression))
                    .orElseGet(ImmutableMap::of);
        }
        catch (SQLException exception) {
            throw new TrinoException(JDBC_ERROR, exception);
        }
    }

    @Override
    public void abortReadConnection(Connection connection)
            throws SQLException
    {
        // Abort connection before closing. Without this, the SQL Server driver
        // attempts to drain the connection by reading all the results.
        connection.abort(directExecutor());
    }

    @Override
    public Connection getConnection(ConnectorSession session, JdbcOutputTableHandle handle)
            throws SQLException
    {
        return configureConnectionTransactionIsolation(super.getConnection(session, handle));
    }

    @Override
    public Connection getConnection(ConnectorSession session, JdbcSplit split)
            throws SQLException
    {
        return configureConnectionTransactionIsolation(super.getConnection(session, split));
    }

    private Connection configureConnectionTransactionIsolation(Connection connection)
            throws SQLException
    {
        if (snapshotIsolationDisabled) {
            return connection;
        }
        try {
            if (hasSnapshotIsolationEnabled(connection)) {
                // SQL Server's READ COMMITTED + SNAPSHOT ISOLATION is equivalent to ordinary READ COMMITTED in e.g. Oracle, PostgreSQL.
                connection.setTransactionIsolation(TRANSACTION_SNAPSHOT);
            }
        }
        catch (SQLException e) {
            connection.close();
            throw e;
        }

        return connection;
    }

    private boolean hasSnapshotIsolationEnabled(Connection connection)
            throws SQLException
    {
        try {
            return snapshotIsolationEnabled.get(SnapshotIsolationEnabledCacheKey.INSTANCE, () -> {
                Handle handle = Jdbi.open(connection);
                return handle.createQuery("SELECT snapshot_isolation_state FROM sys.databases WHERE name = :name")
                        .bind("name", connection.getCatalog())
                        .mapTo(Boolean.class)
                        .findOne()
                        .orElse(false);
            });
        }
        catch (ExecutionException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, e);
        }
    }

    private static String singleQuote(String... objects)
    {
        return singleQuote(DOT_JOINER.join(objects));
    }

    private static String singleQuote(String literal)
    {
        return "\'" + literal + "\'";
    }

    public static ColumnMapping varbinaryColumnMapping()
    {
        return ColumnMapping.sliceMapping(
                VARBINARY,
                (resultSet, columnIndex) -> wrappedBuffer(resultSet.getBytes(columnIndex)),
                varbinaryWriteFunction(),
                DISABLE_PUSHDOWN);
    }

    public static SliceWriteFunction varbinaryWriteFunction()
    {
        return new SliceWriteFunction()
        {
            @Override
            public void set(PreparedStatement statement, int index, Slice value)
                    throws SQLException
            {
                statement.setBytes(index, value.getBytes());
            }

            @Override
            public void setNull(PreparedStatement statement, int index)
                    throws SQLException
            {
                statement.setBytes(index, null);
            }
        };
    }

    private static Optional<DataCompression> getTableDataCompression(Handle handle, JdbcTableHandle table)
    {
        return handle.createQuery("" +
                "SELECT data_compression_desc FROM sys.partitions p " +
                "INNER JOIN sys.tables t ON p.object_id = t.object_id " +
                "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id " +
                "INNER JOIN sys.indexes i ON t.object_id = i.object_id " +
                "WHERE s.name = :schema AND t.name = :table_name " +
                "AND p.index_id = 0 " + // Heap
                "AND i.type = 0 " + // Heap index type
                "AND i.data_space_id NOT IN (SELECT data_space_id FROM sys.partition_schemes)")
                .bind("schema", table.getSchemaName())
                .bind("table_name", table.getTableName())
                .mapTo(String.class)
                .findOne()
                .flatMap(dataCompression -> Enums.getIfPresent(DataCompression.class, dataCompression).toJavaUtil());
    }

    private enum SnapshotIsolationEnabledCacheKey
    {
        // The snapshot isolation can be enabled or disabled on database level. We connect to single
        // database, so from our perspective, this is a global property.
        INSTANCE
    }
}
