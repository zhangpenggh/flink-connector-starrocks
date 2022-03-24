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

package com.starrocks.connector.flink.it.sink;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema;
import org.junit.Test;

import mockit.Expectations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.starrocks.connector.flink.StarRocksSink;
import com.starrocks.connector.flink.StarRocksSinkBaseTest;

class TestEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    public Integer score;
    public String name;

    public TestEntry(Integer score, String name) {
        this.score = score;
        this.name = name;
    }
}

public class StarRocksGenericSinkITTest extends StarRocksSinkBaseTest {

    private final TestEntry[] TEST_DATA = {
        new TestEntry(99, "paul"),
        new TestEntry(98, "lebron"),
        new TestEntry(99, "stephen"),
        new TestEntry(98, "klay")
    };
    
    @Test
    public void testBatchSink() {
        List<Map<String, String>> meta = new ArrayList<>();
        meta.add(new HashMap<String, String>(){{
            put("COLUMN_NAME", "name");
            put("COLUMN_KEY", "");
            put("DATA_TYPE", "varchar");
        }});
        meta.add(new HashMap<String, String>(){{
            put("COLUMN_NAME", "score");
            put("COLUMN_KEY", "");
            put("DATA_TYPE", "bigint");
        }});
        new Expectations(){
            {
                v.getTableColumnsMetaData();
                result = meta;
            }
        };
        mockStarRocksVersion(null);
        mockSuccessResponse();
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRestartStrategy(new RestartStrategies.NoRestartStrategyConfiguration());
        env.setParallelism(1);
        env.fromElements(TEST_DATA)
            .addSink(StarRocksSink.sink(
                TableSchema.builder()
                    .field("score", DataTypes.INT())
                    .field("name", DataTypes.VARCHAR(20))
                    .build(),
                OPTIONS,
                (slots, te) -> {
                    slots[0] = te.score;
                    slots[1] = te.name;
                }));

        String exMsg = "";
        try {
            env.execute();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertFalse(exMsg, exMsg.length() > 0);
        // real case
        // mockSuccessResponse();
        // StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // env.setRestartStrategy(new RestartStrategies.NoRestartStrategyConfiguration());
        // env.setParallelism(1);
        // env.fromElements(TEST_DATA)
        //     .addSink(StarRocksSink.sink(
        //         TableSchema.builder()
        //             .field("k1", DataTypes.TINYINT())
        //             .field("k2", DataTypes.DECIMAL(10, 2))
        //             .field("v1", DataTypes.CHAR(10))
        //             .field("v2", DataTypes.INT())
        //             .build(),
        //         StarRocksSinkOptions.builder()
        //             .withProperty("jdbc-url", "jdbc:mysql://172.26.92.139:28533")
        //             .withProperty("load-url", "172.26.92.139:28531;172.26.92.139:28531")
        //             .withProperty("database-name", "aa")
        //             .withProperty("table-name", "test")
        //             .withProperty("username", "root")
        //             .withProperty("password", "")
        //             .withProperty("sink.properties.column_separator", "\\x02")
        //             .build(),
        //         (slots, te) -> {
        //             slots[0] = te.score;
        //             slots[1] = te.score;
        //             slots[2] = te.name;
        //             slots[3] = te.score;
        //         }));

        // String exMsg = "";
        // try {
        //     env.execute();
        // } catch (Exception e) {
        //     exMsg = e.getMessage();
        // }
        // assertFalse(exMsg, exMsg.length() > 0);
    }

    @Test
    public void testSinkCommonProperties() {
        assertEquals(JDBC_URL, OPTIONS.getJdbcUrl());
        assertEquals(DATABASE, OPTIONS.getDatabaseName());
        assertEquals(TABLE, OPTIONS.getTableName());
        assertEquals(Long.parseLong(SINK_MAX_INTERVAL), OPTIONS.getSinkMaxFlushInterval());
        assertEquals(Long.parseLong(SINK_MAX_BYTES), OPTIONS.getSinkMaxBytes());
        assertEquals(Long.parseLong(SINK_MAX_RETRIES), OPTIONS.getSinkMaxRetries());
        assertEquals(Long.parseLong(SINK_MAX_ROWS), OPTIONS.getSinkMaxRows());
        assertEquals(LOAD_URL.split(";").length, OPTIONS.getLoadUrlList().size());
        assertEquals(SINK_SEMANTIC, OPTIONS.getSemantic());
        assertEquals(SINK_PROPS.size(), OPTIONS.getSinkStreamLoadProperties().size());
    }

}
