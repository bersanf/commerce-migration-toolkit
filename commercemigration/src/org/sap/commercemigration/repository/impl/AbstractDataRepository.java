package org.sap.commercemigration.repository.impl;

import com.google.common.base.Joiner;
import de.hybris.bootstrap.ddl.DatabaseSettings;
import de.hybris.bootstrap.ddl.HybrisPlatformFactory;
import de.hybris.bootstrap.ddl.tools.persistenceinfo.PersistenceInformation;
import org.apache.commons.lang3.StringUtils;
import org.apache.ddlutils.Platform;
import org.apache.ddlutils.model.Database;
import org.sap.commercemigration.constants.CommercemigrationConstants;
import org.sap.commercemigration.dataset.DataColumn;
import org.sap.commercemigration.dataset.DataSet;
import org.sap.commercemigration.dataset.impl.DefaultDataColumn;
import org.sap.commercemigration.dataset.impl.DefaultDataSet;
import org.sap.commercemigration.datasource.MigrationDataSourceFactory;
import org.sap.commercemigration.datasource.impl.DefaultMigrationDataSourceFactory;
import org.sap.commercemigration.profile.DataSourceConfiguration;
import org.sap.commercemigration.repository.DataRepository;
import org.sap.commercemigration.repository.model.TypeSystemTable;
import org.sap.commercemigration.service.DatabaseMigrationDataTypeMapperService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.sap.commercemigration.constants.CommercemigrationConstants.MIGRATION_TABLESPREFIX;

/**
 * Base information an a
 */
public abstract class AbstractDataRepository implements DataRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractDataRepository.class);

    private final Map<String, DataSource> dataSourceHolder = new ConcurrentHashMap<>();

    private final DataSourceConfiguration dataSourceConfiguration;
    private final MigrationDataSourceFactory migrationDataSourceFactory;
    private final DatabaseMigrationDataTypeMapperService databaseMigrationDataTypeMapperService;
    private Platform platform;
    private Database database;

    public AbstractDataRepository(DataSourceConfiguration dataSourceConfiguration, DatabaseMigrationDataTypeMapperService databaseMigrationDataTypeMapperService) {
        this(dataSourceConfiguration, databaseMigrationDataTypeMapperService, new DefaultMigrationDataSourceFactory());
    }

    public AbstractDataRepository(DataSourceConfiguration dataSourceConfiguration, DatabaseMigrationDataTypeMapperService databaseMigrationDataTypeMapperService, MigrationDataSourceFactory migrationDataSourceFactory) {
        this.dataSourceConfiguration = dataSourceConfiguration;
        this.migrationDataSourceFactory = migrationDataSourceFactory;
        this.databaseMigrationDataTypeMapperService = databaseMigrationDataTypeMapperService;
    }

    @Override
    public DataSourceConfiguration getDataSourceConfiguration() {
        return dataSourceConfiguration;
    }

    @Override
    public DataSource getDataSource() {
        return dataSourceHolder.computeIfAbsent("DATASOURCE", s -> migrationDataSourceFactory.create(dataSourceConfiguration));
    }

    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    @Override
    public int executeUpdateAndCommit(String updateStatement) throws SQLException {
        try (Connection conn = getConnection();
             Statement statement = conn.createStatement()
        ) {
            return statement.executeUpdate(updateStatement);
        }
    }

    @Override
    public void runSqlScript(Resource resource) {
        final ResourceDatabasePopulator databasePopulator = new ResourceDatabasePopulator(resource);
        databasePopulator.setIgnoreFailedDrops(true);
        databasePopulator.execute(getDataSource());
    }

    @Override
    public float getDatabaseUtilization() throws SQLException {
        throw new UnsupportedOperationException("Must be added in the specific repository implementation");
    }

    @Override
    public int truncateTable(String table) throws SQLException {
        return executeUpdateAndCommit(String.format("truncate table %s", table));
    }

    @Override
    public long getRowCount(String table) throws SQLException {
        List<String> conditionsList = new ArrayList<>(1);
        processDefaultConditions(table, conditionsList);
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(String.format("select count(*) from %s where %s", table, expandConditions(conditions)))
        ) {
            long value = 0;
            if (resultSet.next()) {
                value = resultSet.getLong(1);
            }
            return value;
        }
    }

    @Override
    public long getRowCountModifiedAfter(String table, Instant time) throws SQLException {
        List<String> conditionsList = new ArrayList<>(1);
        processDefaultConditions(table, conditionsList);
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement(String.format("select count(*) from %s where modifiedts > ? AND %s", table, expandConditions(conditions)))) {
                stmt.setTimestamp(1, Timestamp.from(time));
                ResultSet resultSet = stmt.executeQuery();
                long value = 0;
                if (resultSet.next()) {
                    value = resultSet.getLong(1);
                }
                return value;
            }
        }
    }

    @Override
    public DataSet getAll(String table) throws Exception {
        List<String> conditionsList = new ArrayList<>(1);
        processDefaultConditions(table, conditionsList);
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(String.format("select * from %s where %s", table, expandConditions(conditions)))
        ) {
            return convertToDataSet(resultSet);
        }
    }

    @Override
    public DataSet getAllModifiedAfter(String table, Instant time) throws Exception {
        List<String> conditionsList = new ArrayList<>(1);
        processDefaultConditions(table, conditionsList);
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement(String.format("select * from %s where modifiedts > ? and %s", table, expandConditions(conditions)))) {
                stmt.setTimestamp(1, Timestamp.from(time));
                ResultSet resultSet = stmt.executeQuery();
                return convertToDataSet(resultSet);
            }
        }
    }

    protected DefaultDataSet convertToDataSet(ResultSet resultSet) throws Exception {
        return convertToDataSet(resultSet, Collections.emptySet());
    }

    protected DefaultDataSet convertToDataSet(ResultSet resultSet, Set<String> ignoreColumns) throws Exception {
        int realColumnCount = resultSet.getMetaData().getColumnCount();
        List<DataColumn> columnOrder = new ArrayList<>();
        int columnCount = 0;
        for (int i = 1; i <= realColumnCount; i++) {
            String columnName = resultSet.getMetaData().getColumnName(i);
            int columnType = resultSet.getMetaData().getColumnType(i);
            int precision = resultSet.getMetaData().getPrecision(i);
            int scale = resultSet.getMetaData().getScale(i);
            if (ignoreColumns.stream().anyMatch(columnName::equalsIgnoreCase)) {
                continue;
            }
            columnCount += 1;
            columnOrder.add(new DefaultDataColumn(columnName, columnType, precision, scale));
        }
        List<List<Object>> results = new ArrayList<>();
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (DataColumn dataColumn : columnOrder) {
                int idx = resultSet.findColumn(dataColumn.getColumnName());
                Object object = resultSet.getObject(idx);
                //TODO: improve CLOB/BLOB handling
                Object mappedValue = databaseMigrationDataTypeMapperService.dataTypeMapper(object, resultSet.getMetaData().getColumnType(idx));
                row.add(mappedValue);
            }
            results.add(row);
        }
        return new DefaultDataSet(columnCount, columnOrder, results);
    }

    @Override
    public void disableIndexesOfTable(String table) throws SQLException {
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(getDisableIndexesScript(table))
        ) {
            while (resultSet.next()) {
                String q = resultSet.getString(1);
                LOG.debug("Running query: {}", q);
                executeUpdateAndCommit(q);
            }
        }
    }

    @Override
    public void enableIndexesOfTable(String table) throws SQLException {
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(getEnableIndexesScript(table))
        ) {
            while (resultSet.next()) {
                String q = resultSet.getString(1);
                LOG.debug("Running query: {}", q);
                executeUpdateAndCommit(q);
            }
        }
    }

    @Override
    public void dropIndexesOfTable(String table) throws SQLException {
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(getDropIndexesScript(table))
        ) {
            while (resultSet.next()) {
                String q = resultSet.getString(1);
                LOG.debug("Running query: {}", q);
                executeUpdateAndCommit(q);
            }
        }
    }

    protected String getDisableIndexesScript(String table) {
        throw new UnsupportedOperationException("not implemented");


    }

    protected String getEnableIndexesScript(String table) {
        throw new UnsupportedOperationException("not implemented");
    }

    protected String getDropIndexesScript(String table) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Platform asPlatform() {
        return asPlatform(false);
    }

    @Override
    public Platform asPlatform(boolean reload) {
        //TODO all properties to be set and check
        if (this.platform == null || reload) {
            final DatabaseSettings databaseSettings = new DatabaseSettings(getDatabaseProvider(), getDataSourceConfiguration().getConnectionString(), getDataSourceConfiguration().getDriver(), getDataSourceConfiguration().getUserName(), getDataSourceConfiguration().getPassword(), getDataSourceConfiguration().getTablePrefix(), ";");
            this.platform = createPlatform(databaseSettings, getDataSource());
            addCustomPlatformTypeMapping(this.platform);
        }
        return this.platform;
    }

    protected Platform createPlatform(DatabaseSettings databaseSettings, DataSource dataSource) {
        return HybrisPlatformFactory.createInstance(databaseSettings, dataSource);
    }


    protected void addCustomPlatformTypeMapping(Platform platform) {
    }

    @Override
    public Database asDatabase() {
        return asDatabase(false);
    }

    @Override
    public Database asDatabase(boolean reload) {
        if (this.database == null || reload) {
            this.database = getDatabase(reload);
        }
        return this.database;
    }

    protected Database getDatabase(boolean reload) {
        String schema = getDataSourceConfiguration().getSchema();
        return asPlatform(reload).readModelFromDatabase(getDataSourceConfiguration().getProfile(), null,
                schema, null);
    }

    @Override
    public Set<String> getAllTableNames() throws SQLException {
        Set<String> allTableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String allTableNamesQuery = createAllTableNamesQuery();
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(allTableNamesQuery)
        ) {
            while (resultSet.next()) {
                String tableName = resultSet.getString(1);
                if (!StringUtils.startsWithIgnoreCase(tableName, MIGRATION_TABLESPREFIX)) {
                    allTableNames.add(resultSet.getString(1));
                }
            }
        }
        return allTableNames;
    }

    @Override
    public Set<TypeSystemTable> getAllTypeSystemTables() throws SQLException {
        if (StringUtils.isEmpty(getDataSourceConfiguration().getTypeSystemName())) {
            throw new RuntimeException("No type system name specified. Check the properties");
        }
        String tablePrefix = getDataSourceConfiguration().getTablePrefix();
        String yDeploymentsTable = StringUtils.defaultIfBlank(tablePrefix, "") + CommercemigrationConstants.DEPLOYMENTS_TABLE;
        Set<String> allTableNames = getAllTableNames();
        if (!allTableNames.contains(yDeploymentsTable)) {
            return Collections.emptySet();
        }
        String allTypeSystemTablesQuery = String.format("SELECT * FROM %s WHERE Typecode IS NOT NULL AND TableName IS NOT NULL AND TypeSystemName = '%s'", yDeploymentsTable, getDataSourceConfiguration().getTypeSystemName());
        Set<TypeSystemTable> allTypeSystemTables = new HashSet<>();
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(allTypeSystemTablesQuery)
        ) {
            while (resultSet.next()) {
                TypeSystemTable typeSystemTable = new TypeSystemTable();
                String name = resultSet.getString("Name");
                String tableName = resultSet.getString("TableName");
                typeSystemTable.setTypeCode(resultSet.getString("Typecode"));
                typeSystemTable.setTableName(tableName);
                typeSystemTable.setName(name);
                typeSystemTable.setTypeSystemName(resultSet.getString("TypeSystemName"));
                typeSystemTable.setAuditTableName(resultSet.getString("AuditTableName"));
                typeSystemTable.setPropsTableName(resultSet.getString("PropsTableName"));
                typeSystemTable.setTypeSystemSuffix(detectTypeSystemSuffix(tableName, name));
                typeSystemTable.setTypeSystemRelatedTable(PersistenceInformation.isTypeSystemRelatedDeployment(name));
                allTypeSystemTables.add(typeSystemTable);
            }
        }
        return allTypeSystemTables;
    }

    private String detectTypeSystemSuffix(String tableName, String name) {
        if (PersistenceInformation.isTypeSystemRelatedDeployment(name)) {
            return getDataSourceConfiguration().getTypeSystemSuffix();
        }
        return StringUtils.EMPTY;
    }

    @Override
    public boolean isAuditTable(String table) throws Exception {
        String tablePrefix = getDataSourceConfiguration().getTablePrefix();
        String query = String.format("SELECT count(*) from %s%s WHERE AuditTableName = ? OR AuditTableName = ?", StringUtils.defaultIfBlank(tablePrefix, ""), CommercemigrationConstants.DEPLOYMENTS_TABLE);
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(query);
        ) {
            stmt.setObject(1, StringUtils.removeStartIgnoreCase(table, tablePrefix));
            stmt.setObject(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                boolean isAudit = false;
                if (rs.next()) {
                    isAudit = rs.getInt(1) > 0;
                }
                return isAudit;
            }
        }
    }

    protected abstract String createAllTableNamesQuery();

    @Override
    public Set<String> getAllColumnNames(String table) throws SQLException {
        String allColumnNamesQuery = createAllColumnNamesQuery(table);
        Set<String> allColumnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement();
             ResultSet resultSet = stmt.executeQuery(allColumnNamesQuery)
        ) {
            while (resultSet.next()) {
                allColumnNames.add(resultSet.getString(1));
            }
        }
        return allColumnNames;
    }

    protected abstract String createAllColumnNamesQuery(String table);

    @Override
    public DataSet getBatchWithoutIdentifier(String table, Set<String> allColumns, long batchSize, long offset) throws Exception {
        return getBatchWithoutIdentifier(table, allColumns, batchSize, offset, null);
    }

    @Override
    public DataSet getBatchWithoutIdentifier(String table, Set<String> allColumns, long batchSize, long offset, Instant time) throws Exception {
        //get batches with modifiedts >= configured time for incremental migration
        List<String> conditionsList = new ArrayList<>(1);
        processDefaultConditions(table, conditionsList);
        if (time != null) {
            conditionsList.add("modifiedts > ?");
        }
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(buildOffsetBatchQuery(table, allColumns, batchSize, offset, conditions))) {
            stmt.setFetchSize(Long.valueOf(batchSize).intValue());
            if (time != null) {
                stmt.setTimestamp(1, Timestamp.from(time));
            }
            ResultSet resultSet = stmt.executeQuery();
            return convertToBatchDataSet(resultSet);
        }
    }

    @Override
    public DataSet getBatchOrderedByColumn(String table, String column, Object lastColumnValue, long batchSize) throws Exception {
        return getBatchOrderedByColumn(table, column, lastColumnValue, batchSize, null);
    }

    @Override
    public DataSet getBatchOrderedByColumn(String table, String column, Object lastColumnValue, long batchSize, Instant time) throws Exception {
        //get batches with modifiedts >= configured time for incremental migration
        List<String> conditionsList = new ArrayList<>(2);
        processDefaultConditions(table, conditionsList);
        if (time != null) {
            conditionsList.add("modifiedts > ?");
        }
        if (lastColumnValue != null) {
            conditionsList.add(String.format("%s >= %s", column, lastColumnValue));
        }
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(buildValueBatchQuery(table, column, batchSize, conditions))) {
            stmt.setFetchSize(Long.valueOf(batchSize).intValue());
            if (time != null) {
                stmt.setTimestamp(1, Timestamp.from(time));
            }
            ResultSet resultSet = stmt.executeQuery();
            return convertToBatchDataSet(resultSet);
        }
    }


    @Override
    public DataSet getBatchMarkersOrderedByColumn(String table, String column, long batchSize) throws Exception {
        return getBatchMarkersOrderedByColumn(table, column, batchSize, null);
    }

    @Override
    public DataSet getBatchMarkersOrderedByColumn(String table, String column, long batchSize, Instant time) throws Exception {
        //get batches with modifiedts >= configured time for incremental migration
        List<String> conditionsList = new ArrayList<>(2);
        processDefaultConditions(table, conditionsList);
        if (time != null) {
            conditionsList.add("modifiedts > ?");
        }
        String[] conditions = null;
        if (conditionsList.size() > 0) {
            conditions = conditionsList.toArray(new String[conditionsList.size()]);
        }
        try (Connection connection = getConnection();
             PreparedStatement stmt = connection.prepareStatement(buildBatchMarkersQuery(table, column, batchSize, conditions))) {
            stmt.setFetchSize(Long.valueOf(batchSize).intValue());
            if (time != null) {
                stmt.setTimestamp(1, Timestamp.from(time));
            }
            ResultSet resultSet = stmt.executeQuery();
            return convertToBatchDataSet(resultSet);
        }
    }

    @Override
    public DataSet getUniqueColumns(String table) throws Exception {
        try (Connection connection = getConnection();
             Statement stmt = connection.createStatement()) {
            ResultSet resultSet = stmt.executeQuery(createUniqueColumnsQuery(table));
            return convertToDataSet(resultSet);
        }
    }

    protected abstract String buildOffsetBatchQuery(String table, Set<String> columns, long batchSize, long offset, String... conditions);

    protected abstract String buildValueBatchQuery(String table, String column, long batchSize, String... conditions);

    protected abstract String buildBatchMarkersQuery(String table, String column, long batchSize, String... conditions);

    protected abstract String createUniqueColumnsQuery(String tableName);

    private void processDefaultConditions(String table, List<String> conditionsList) {
        String tsCondition = getTsCondition(table);
        if (StringUtils.isNotEmpty(tsCondition)) {
            conditionsList.add(tsCondition);
        }
    }


    private String getTsCondition(String table) {
        Objects.requireNonNull(table);
        if (table.toLowerCase().endsWith(CommercemigrationConstants.DEPLOYMENTS_TABLE)) {
            return String.format("TypeSystemName = '%s'", getDataSourceConfiguration().getTypeSystemName());
        }
        return null;
    }

    protected String expandConditions(String[] conditions) {
        if (conditions == null || conditions.length == 0) {
            return "1=1";
        } else {
            return Joiner.on(" and ").join(conditions);
        }
    }

    protected DataSet convertToBatchDataSet(ResultSet resultSet) throws Exception {
        return convertToDataSet(resultSet);
    }

    @Override
    public boolean validateConnection() throws Exception {
        try (Connection connection = getConnection()) {
            return connection.isValid(120);
        }
    }

}
