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

package com.starrocks.connector.flink.manager.sink;

import java.util.ArrayList;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.calcite.shaded.com.google.common.base.Strings;
import org.apache.flink.calcite.shaded.com.google.common.collect.Lists;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema.Builder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.starrocks.connector.flink.StarRocksSinkBaseTest;
import com.starrocks.connector.flink.manager.StarRocksSinkManager;
import com.starrocks.connector.flink.table.sink.StarRocksSinkOptions;

import mockit.Expectations;
import mockit.MockUp;
import mockit.Mock;

public class StarRocksSinkManagerTest extends StarRocksSinkBaseTest {

    @Test
    public void testValidateTableStructure() {
        mockTableStructure();
        mockStarRocksVersion(null);
        OPTIONS.getSinkStreamLoadProperties().remove("columns");
        assertTrue(!OPTIONS.hasColumnMappingProperty());
        // test succeeded
        try {
            new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        } catch (Exception e) {
            throw e;
        }
        // test failed
        new Expectations(){
            {
                v.getTableColumnsMetaData();
                result = Lists.newArrayList();
            }
        };
        String exMsg = "";
        try {
            new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(exMsg.length() > 0);
        // test failed
        new Expectations(){
            {
                v.getTableColumnsMetaData();
                result = STARROCKS_TABLE_META.keySet().stream().map(k -> new HashMap<String, Object>(){{
                    put("COLUMN_NAME", k);
                    put("COLUMN_KEY", "");
                    put("DATA_TYPE", "varchar");
                }}).collect(Collectors.toList());;
            }
        };
        exMsg = "";
        try {
            new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(exMsg.length() > 0);


        Builder schemaBuilder = createTableSchemaBuilder();
        schemaBuilder.field("v6", DataTypes.VARCHAR(20));
        try {
            new StarRocksSinkManager(OPTIONS, schemaBuilder.build());
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertEquals("Fields count of test_tbl mismatch. \n"
                + "flinkSchema[8]:k1,k2,v1,v2,v3,v4,v5,v6\n"
                + " realTab[7]:k1,k2,v1,v2,v3,v4,v5",exMsg);
    }

    @Test
    public void testWriteMaxBatch() throws IOException {
        mockTableStructure();
        mockStarRocksVersion(null);
        long maxRows = OPTIONS.getSinkMaxRows();
        stopHttpServer();
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            for (int i = 0; i < maxRows - 1; i++) {
                mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "test record");
            }
        } catch (Exception e) {
            throw e;
        }
        String exMsg = "";
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            for (int i = 0; i < maxRows * 3; i++) {
                mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "test record"+i);
            }
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(0 < exMsg.length());
    }

    @Test
    public void testWriteMaxBytes() throws IOException {
        mockTableStructure();
        mockStarRocksVersion(null);
        long maxSize = OPTIONS.getSinkMaxBytes();
        stopHttpServer();
        int rowLength = 100000;
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            for (int i = 0; i < maxSize / rowLength - 1; i++) {
                mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), new String(new char[rowLength]));
            }
        } catch (Exception e) {
            throw e;
        }
        String exMsg = "";
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            for (int i = 0; i < maxSize / rowLength + 1; i++) {
                mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), new String(new char[rowLength]));
            }
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), new String(new char[rowLength]));
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(0 < exMsg.length());
    }

    @Test
    public void testWriteMaxRetries() throws IOException {
        mockTableStructure();
        mockStarRocksVersion(null);
        int maxRetries = OPTIONS.getSinkMaxRetries();
        if (maxRetries <= 0) return;
        stopHttpServer();
        mockSuccessResponse();
        String exMsg = "";
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            for (int i = 0; i < OPTIONS.getSinkMaxRows(); i++) {
                mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            }
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(0 < exMsg.length());
    }

    @Test
    public void testFlush() throws Exception {
        mockTableStructure();
        mockStarRocksVersion(null);
        mockSuccessResponse();
        String exMsg = "";
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.writeRecords("db1", "table1", "");
            mgr.writeRecords("db2", "table2", "");
            mgr.writeRecords("db3", "table3", "");
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
            throw e;
        }
        assertEquals(0, exMsg.length());
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.writeRecords("db1", "table1", "");
            mgr.writeRecords("db2", "table2", "");
            mgr.writeRecords("db3", "table3", "");
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
            throw e;
        }
        assertEquals(0, exMsg.length());
        stopHttpServer();
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.writeRecords("db1", "table1", "");
            mgr.writeRecords("db2", "table2", "");
            mgr.writeRecords("db3", "table3", "");
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(0 < exMsg.length());
    }

    @Test
    public void testClose() throws Exception {
        mockTableStructure();
        mockStarRocksVersion(null);
        mockSuccessResponse();
        String exMsg = "";

        // test close
        StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        try {
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.flush(null, true);
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertEquals(0, exMsg.length());
        assertTrue((boolean)getPrivateFieldValue(mgr, "closed"));
        TimeUnit.MILLISECONDS.sleep(100L); // wait flush thread exit
        assertFalse((boolean)getPrivateFieldValue(mgr, "flushThreadAlive"));
    }

    @Test
    public void testLabelExist() {
        mockTableStructure();
        mockStarRocksVersion(null);
        mockLabelExistsResponse(new String[]{"PREPARE", "VISIBLE"});
        String exMsg = "";
        try {
            StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertTrue(0 == exMsg.length());
    }

    @Test
    public void testOffer() throws Exception {
        mockTableStructure();
        mockStarRocksVersion(null);
        String exMsg = "";
        long offerTimeoutMs = 500L;
        mockWaitSuccessResponse(offerTimeoutMs + 100L);

        new MockUp<StarRocksSinkOptions>(OPTIONS.getClass()) {
            @Mock
            public long getSinkOfferTimeout() {
                return offerTimeoutMs;
            }

        };
        // flush cost more than offer timeout
        StarRocksSinkManager mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        setPrivateFieldValue(mgr, "FLUSH_QUEUE_POLL_TIMEOUT", 10);
        try {
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.flush(null, true);
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.flush(null, true);
        } catch (Exception e) {
            exMsg = e.getMessage();
            e.printStackTrace();
        }
        assertTrue(0 < exMsg.length());
        assertTrue(exMsg.startsWith("Timeout while offering data to flushQueue"));


        exMsg = "";
        mockWaitSuccessResponse(offerTimeoutMs - 100L);
        // flush cost less than offer timeout
        mgr = new StarRocksSinkManager(OPTIONS, TABLE_SCHEMA);
        try {
            mgr.startAsyncFlushing();
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.flush(null, true);
            mgr.writeRecords(OPTIONS.getDatabaseName(), OPTIONS.getTableName(), "");
            mgr.flush(null, true);
            mgr.close();
        } catch (Exception e) {
            exMsg = e.getMessage();
        }
        assertEquals(0, exMsg.length());
        assertTrue((boolean)getPrivateFieldValue(mgr, "closed"));
        TimeUnit.MILLISECONDS.sleep(100L); // wait flush thread exit
        assertFalse((boolean)getPrivateFieldValue(mgr, "flushThreadAlive"));
    }
}
