/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.test.it.data.pipeline.core.consistencycheck.table.calculator;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.data.pipeline.api.metadata.SchemaTableName;
import org.apache.shardingsphere.data.pipeline.api.metadata.model.PipelineColumnMetaData;
import org.apache.shardingsphere.data.pipeline.common.datasource.PipelineDataSourceWrapper;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.result.SingleTableInventoryCalculatedResult;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.calculator.RecordSingleTableInventoryCalculator;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.calculator.SingleTableInventoryCalculateParameter;
import org.apache.shardingsphere.infra.database.core.type.DatabaseType;
import org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.internal.configuration.plugins.Plugins;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordSingleTableInventoryCalculatorTest {
    
    private static PipelineDataSourceWrapper source;
    
    private static PipelineDataSourceWrapper target;
    
    @BeforeAll
    static void setUp() throws Exception {
        source = new PipelineDataSourceWrapper(createHikariDataSource("source_ds"), TypedSPILoader.getService(DatabaseType.class, "H2"));
        createTableAndInitData(source, "t_order_copy");
        target = new PipelineDataSourceWrapper(createHikariDataSource("target_ds"), TypedSPILoader.getService(DatabaseType.class, "H2"));
        createTableAndInitData(target, "t_order");
    }
    
    @AfterAll
    static void tearDown() throws Exception {
        source.close();
        target.close();
    }
    
    private static HikariDataSource createHikariDataSource(final String databaseName) {
        HikariDataSource result = new HikariDataSource();
        result.setJdbcUrl(String.format("jdbc:h2:mem:%s;DATABASE_TO_UPPER=false;MODE=MySQL", databaseName));
        result.setUsername("root");
        result.setPassword("root");
        result.setMaximumPoolSize(10);
        result.setMinimumIdle(2);
        result.setConnectionTimeout(15 * 1000);
        result.setIdleTimeout(40 * 1000);
        return result;
    }
    
    private static void createTableAndInitData(final PipelineDataSourceWrapper dataSource, final String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String sql = String.format("CREATE TABLE %s (order_id INT NOT NULL, user_id INT NOT NULL, status VARCHAR(45) NULL, PRIMARY KEY (order_id))", tableName);
            connection.createStatement().execute(sql);
            PreparedStatement preparedStatement = connection.prepareStatement(String.format("INSERT INTO %s (order_id, user_id, status) VALUES (?, ?, ?)", tableName));
            for (int i = 0; i < 10; i++) {
                preparedStatement.setInt(1, i + 1);
                preparedStatement.setInt(2, i + 1);
                preparedStatement.setString(3, "test");
                preparedStatement.execute();
            }
        }
    }
    
    @Test
    void assertCalculateFromBegin() throws ReflectiveOperationException {
        RecordSingleTableInventoryCalculator calculator = new RecordSingleTableInventoryCalculator(1000);
        Plugins.getMemberAccessor().set(RecordSingleTableInventoryCalculator.class.getDeclaredField("chunkSize"), calculator, 5);
        SingleTableInventoryCalculateParameter sourceParam = generateParameter(source, "t_order_copy", 0);
        Optional<SingleTableInventoryCalculatedResult> sourceCalculateResult = calculator.calculateChunk(sourceParam);
        SingleTableInventoryCalculateParameter targetParam = generateParameter(target, "t_order", 0);
        Optional<SingleTableInventoryCalculatedResult> targetCalculateResult = calculator.calculateChunk(targetParam);
        assertTrue(sourceCalculateResult.isPresent());
        assertTrue(targetCalculateResult.isPresent());
        assertTrue(sourceCalculateResult.get().getMaxUniqueKeyValue().isPresent());
        assertTrue(targetCalculateResult.get().getMaxUniqueKeyValue().isPresent());
        assertThat(sourceCalculateResult.get().getMaxUniqueKeyValue().get(), is(targetCalculateResult.get().getMaxUniqueKeyValue().get()));
        assertThat(targetCalculateResult.get().getMaxUniqueKeyValue().get(), is(5L));
        assertThat(sourceCalculateResult.get(), is(targetCalculateResult.get()));
    }
    
    @Test
    void assertCalculateFromMiddle() throws ReflectiveOperationException {
        RecordSingleTableInventoryCalculator calculator = new RecordSingleTableInventoryCalculator(1000);
        Plugins.getMemberAccessor().set(RecordSingleTableInventoryCalculator.class.getDeclaredField("chunkSize"), calculator, 5);
        SingleTableInventoryCalculateParameter sourceParam = generateParameter(source, "t_order_copy", 5);
        Optional<SingleTableInventoryCalculatedResult> sourceCalculateResult = calculator.calculateChunk(sourceParam);
        SingleTableInventoryCalculateParameter targetParam = generateParameter(target, "t_order", 5);
        Optional<SingleTableInventoryCalculatedResult> targetCalculateResult = calculator.calculateChunk(targetParam);
        assertTrue(sourceCalculateResult.isPresent());
        assertTrue(targetCalculateResult.isPresent());
        assertTrue(sourceCalculateResult.get().getMaxUniqueKeyValue().isPresent());
        assertTrue(targetCalculateResult.get().getMaxUniqueKeyValue().isPresent());
        assertThat(sourceCalculateResult.get().getMaxUniqueKeyValue().get(), is(targetCalculateResult.get().getMaxUniqueKeyValue().get()));
        assertThat(targetCalculateResult.get().getMaxUniqueKeyValue().get(), is(10L));
        assertThat(sourceCalculateResult.get(), is(targetCalculateResult.get()));
    }
    
    private SingleTableInventoryCalculateParameter generateParameter(final PipelineDataSourceWrapper dataSource, final String logicTableName, final Object dataCheckPosition) {
        List<PipelineColumnMetaData> uniqueKeys = Collections.singletonList(new PipelineColumnMetaData(1, "order_id", Types.INTEGER, "integer", false, true, true));
        return new SingleTableInventoryCalculateParameter(dataSource, new SchemaTableName(null, logicTableName), Collections.emptyList(), uniqueKeys, dataCheckPosition);
    }
}