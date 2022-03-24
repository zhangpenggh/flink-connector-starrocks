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

package com.starrocks.connector.flink.it.source;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson.JSONObject;
import com.starrocks.connector.flink.table.source.StarRocksSourceOptions;
import com.starrocks.connector.flink.table.source.StarrocksExternalServiceImpl;
import com.starrocks.connector.flink.table.source.struct.SelectColumn;
import com.starrocks.thrift.TStarrocksExternalService;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.TableSchema;
import com.starrocks.shade.org.apache.thrift.protocol.TBinaryProtocol;
import com.starrocks.shade.org.apache.thrift.server.TServer;
import com.starrocks.shade.org.apache.thrift.server.TThreadPoolServer;
import com.starrocks.shade.org.apache.thrift.transport.TServerSocket;
import com.starrocks.shade.org.apache.thrift.transport.TServerTransport;
import com.starrocks.shade.org.apache.thrift.transport.TTransportException;
import com.starrocks.shade.org.apache.thrift.transport.TTransportFactory;
import org.junit.After;
import org.junit.Before;


public abstract class StarRocksSourceBaseTest {

    protected TableSchema TABLE_SCHEMA;
    protected TableSchema TABLE_SCHEMA_NOT_NULL;
    protected StarRocksSourceOptions OPTIONS;
    protected StarRocksSourceOptions OPTIONS_WITH_COLUMN_IS_COUNT;
    protected int[][] PROJECTION_ARRAY;
    protected SelectColumn[] SELECT_COLUMNS;
    protected int[][] PROJECTION_ARRAY_NULL;
    protected final String DATABASE = "test";
    protected final String TABLE = "test_source";
    protected final String USERNAME = "root";
    protected final String PASSWORD = "root123";
    private ServerSocket httpSocket;
    protected final int AVAILABLE_QUERY_PORT = 53329;
    protected final String JDBC_URL = "jdbc:mysql://127.0.0.1:53329,127.0.0.1:" + AVAILABLE_QUERY_PORT;
    protected final int AVAILABLE_HTTP_PORT = 29592;
    protected String SCAN_URL = "127.0.0.1:" + AVAILABLE_HTTP_PORT;
    private TServer thriftServer;
    private ServerSocket thriftSocket;
    protected int AVAILABLE_THRIFT_PORT = 21592;
    protected String mockResonse = "";
    protected String querySQL = "select * from `test`.`test_source`";
    protected int tabletCount = 50;

    @Before
    public void initializeCommon() {
        PROJECTION_ARRAY = new int[2][1];
        PROJECTION_ARRAY[0] = new int[]{2};
        PROJECTION_ARRAY[1] = new int[]{7};
        PROJECTION_ARRAY_NULL = new int[0][0];
        SELECT_COLUMNS = new SelectColumn[]{
            new SelectColumn("char_1", 2),
            new SelectColumn("int_1", 7),
        };
    }

    private void initializeCommonOptions() {
        StarRocksSourceOptions options = StarRocksSourceOptions.builder()
                .withProperty("scan-url", SCAN_URL)
                .withProperty("jdbc-url", JDBC_URL)
                .withProperty("username", USERNAME)
                .withProperty("password", PASSWORD)
                .withProperty("table-name", TABLE)
                .withProperty("database-name", DATABASE)
                .build();
        OPTIONS = options;
    }

    private void initializeColumnIsCountOptions() {
        StarRocksSourceOptions options = StarRocksSourceOptions.builder()
                .withProperty("scan-url", SCAN_URL)
                .withProperty("jdbc-url", JDBC_URL)
                .withProperty("username", USERNAME)
                .withProperty("password", PASSWORD)
                .withProperty("table-name", DATABASE)
                .withProperty("database-name", TABLE)
                .withProperty("scan.columns", "count(1)")
                .build();
        OPTIONS_WITH_COLUMN_IS_COUNT = options;
    }

    @Before
    public void initializeTableSchema() {
        TableSchema tableSchema = TableSchema.builder()
            .field("date_1", DataTypes.DATE())
            .field("datetime_1", DataTypes.TIMESTAMP(6))
            .field("char_1", DataTypes.CHAR(20))
            .field("varchar_1", DataTypes.STRING())
            .field("boolean_1", DataTypes.BOOLEAN())
            .field("tinyint_1", DataTypes.TINYINT())
            .field("smallint_1", DataTypes.SMALLINT())
            .field("int_1", DataTypes.INT())
            .field("bigint_1", DataTypes.BIGINT())
            .field("largeint_1", DataTypes.STRING())
            .field("float_1", DataTypes.FLOAT())
            .field("double_1", DataTypes.DOUBLE())
            .field("decimal_1", DataTypes.DECIMAL(27, 9))
            .build();
        TABLE_SCHEMA = tableSchema;
    }

    @Before
    public void initializeTableSchemaNotNull() {
        TableSchema tableSchema = TableSchema.builder()
            .field("date_1", DataTypes.DATE().notNull())
            .field("datetime_1", DataTypes.TIMESTAMP(6).notNull())
            .field("char_1", DataTypes.CHAR(20).notNull())
            .field("varchar_1", DataTypes.STRING().notNull())
            .field("boolean_1", DataTypes.BOOLEAN().notNull())
            .field("tinyint_1", DataTypes.TINYINT().notNull())
            .field("smallint_1", DataTypes.SMALLINT().notNull())
            .field("int_1", DataTypes.INT().notNull())
            .field("bigint_1", DataTypes.BIGINT().notNull())
            .field("largeint_1", DataTypes.STRING().notNull())
            .field("float_1", DataTypes.FLOAT().notNull())
            .field("double_1", DataTypes.DOUBLE().notNull())
            .field("decimal_1", DataTypes.DECIMAL(27, 9).notNull())
            .build();
        TABLE_SCHEMA_NOT_NULL = tableSchema;
    }

    @Before
    public void createHttpServer() throws IOException {
        tryBindingHttpServerSocket();
        new Thread(new Runnable(){
            @Override
            public void run() {
                try {
                    while (true) {
                        if (null == httpSocket || httpSocket.isClosed()) break;
                        Socket ac = httpSocket.accept();
                        final Socket socket = ac;
                        InputStream in = socket.getInputStream();
                        BufferedReader bd = new BufferedReader(new InputStreamReader(in));
                        String requestHeader;
                        int contentLength = 0;
                        while ((requestHeader = bd.readLine()) != null && !requestHeader.isEmpty()) {
                            if (requestHeader.startsWith("Content-Length")) {
                                int begin = requestHeader.indexOf("Content-Length") + "Content-Length:".length();
                                String postParamsLength = requestHeader.substring(begin).trim();
                                contentLength = Integer.parseInt(postParamsLength);                                
                            }
                        }
                        StringBuffer sb = new StringBuffer();
                        if (contentLength > 0) {
                            for (int i = 0; i < contentLength; i ++) {
                                sb.append((char) bd.read());
                            }
                        }
                        assertEquals("{\"sql\":\""+ querySQL +"\"}", sb.toString());
                        PrintWriter pw = new PrintWriter(socket.getOutputStream());
                        pw.println("HTTP/1.1 200 OK");
                        pw.println("Content-type:application/json");
                        pw.println();
                        pw.println(mockResonse);
                        pw.flush();
                        socket.close();
                    }
                } catch (Exception e) {}
            }
        }).start();
    }

    @Before
    public void createThriftServer() {
        tryBindingThriftServerSocket();
        Thread thriftThread = new Thread(() -> {
            TStarrocksExternalService.Processor<StarrocksExternalServiceImpl> processor = new TStarrocksExternalService.Processor<>(new StarrocksExternalServiceImpl());
            try (TServerTransport transport = new TServerSocket(thriftSocket)) {
                TThreadPoolServer.Args tArgs = new TThreadPoolServer.Args(transport);
                tArgs.processor(processor);
                tArgs.protocolFactory(new TBinaryProtocol.Factory());
                tArgs.transportFactory(new TTransportFactory());
                tArgs.minWorkerThreads(10);
                tArgs.maxWorkerThreads(20);
                thriftServer = new TThreadPoolServer(tArgs);
                thriftServer.serve();
            } catch (TTransportException e) {
                e.printStackTrace();
            }
        });
        thriftThread.start();
    }

    protected void mockResonsefunc() {
        String[] beNode = new String[]{
            "127.0.0.1:" + AVAILABLE_THRIFT_PORT, 
            "127.0.0.2:" + AVAILABLE_THRIFT_PORT, 
            "127.0.0.3:" + AVAILABLE_THRIFT_PORT};
        Map<String, Object> respMap = new HashMap<>();
        respMap.put("opaqued_query_plan", "mockPlan");
        respMap.put("status", "200");
        Map<Integer, Object> partitionsSet = new HashMap<>();
        for (int i = 0; i < tabletCount; i ++) {
            Map<String, Object> pMap = new HashMap<>();
            pMap.put("version", 4);
            pMap.put("versionHash", 6318449679607016199L);
            pMap.put("schemaHash", 975114127);
            pMap.put("routings", new String[]{
                beNode[i%beNode.length], 
                beNode[i%beNode.length + 1 >= beNode.length ? 0 : i%beNode.length + 1]
            });
            partitionsSet.put(i, pMap);
        }
        respMap.put("partitions", partitionsSet);
        JSONObject json = new JSONObject(respMap);
        mockResonse = json.toJSONString();
    }

    protected void mockOneBeResonsefunc() {
        String[] beNode = new String[]{
            "127.0.0.1:" + AVAILABLE_THRIFT_PORT};
        Map<String, Object> respMap = new HashMap<>();
        respMap.put("opaqued_query_plan", "mockPlan");
        respMap.put("status", "200");
        Map<Integer, Object> partitionsSet = new HashMap<>();
        for (int i = 0; i < tabletCount; i ++) {
            Map<String, Object> pMap = new HashMap<>();
            pMap.put("version", 4);
            pMap.put("versionHash", 6318449679607016199L);
            pMap.put("schemaHash", 975114127);
            pMap.put("routings", new String[]{
                beNode[i%beNode.length], 
                beNode[i%beNode.length + 1 >= beNode.length ? 0 : i%beNode.length + 1]
            });
            partitionsSet.put(i, pMap);
        }
        respMap.put("partitions", partitionsSet);
        JSONObject json = new JSONObject(respMap);
        mockResonse = json.toJSONString();
    }

    private void tryBindingHttpServerSocket() {
        int maxTryingPorts = 100;
        for (int i = 0; i < maxTryingPorts; i++) {
            try {
                int port = AVAILABLE_HTTP_PORT + i;
                httpSocket = new ServerSocket(port);
                SCAN_URL = "127.0.0.1:" + port;
                initializeCommonOptions();
                initializeColumnIsCountOptions();
                break;
            } catch (IOException e) {}
        }
    }

    private void tryBindingThriftServerSocket() {
        int maxTryingPorts = 100;
        for (int i = 0; i < maxTryingPorts; i++) {
            try {
                int port = AVAILABLE_THRIFT_PORT + i;
                thriftSocket = new ServerSocket(port);
                AVAILABLE_THRIFT_PORT = port;
                break;
            } catch (IOException e) {}
        }
    }

    @After
    public void stopHttpServer() throws IOException {
        if (httpSocket != null) {
            httpSocket.close();
        }
        httpSocket = null;
    }

    @After
    public void stopThriftServer() {
        if (thriftServer != null) {
            thriftServer.stop();
        }
        thriftServer = null;
        if (thriftSocket != null) {
            try {
                thriftSocket.close();
            } catch (IOException e) {}
        }
        thriftSocket = null;
    }
}
