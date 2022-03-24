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

package com.starrocks.connector.flink.table.sink;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.binary.NestedRowData;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.InstantiationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.calcite.shaded.com.google.common.base.Strings;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.truncate.Truncate;

import java.util.HashMap;
import java.util.Map;

import com.starrocks.connector.flink.manager.StarRocksSinkBufferEntity;
import com.starrocks.connector.flink.manager.StarRocksSinkManager;
import com.starrocks.connector.flink.row.sink.StarRocksIRowTransformer;
import com.starrocks.connector.flink.row.sink.StarRocksISerializer;
import com.starrocks.connector.flink.row.sink.StarRocksSerializerFactory;

public class StarRocksDynamicSinkFunction<T> extends RichSinkFunction<T> implements CheckpointedFunction {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(StarRocksDynamicSinkFunction.class);

    private StarRocksSinkManager sinkManager;
    private StarRocksIRowTransformer<T> rowTransformer;
    private StarRocksSinkOptions sinkOptions;
    private StarRocksISerializer serializer;
    private transient Counter totalInvokeRowsTime;
    private transient Counter totalInvokeRows;
    private static final String COUNTER_INVOKE_ROWS_COST_TIME = "totalInvokeRowsTimeNs";
    private static final String COUNTER_INVOKE_ROWS = "totalInvokeRows";

    // state only works with `StarRocksSinkSemantic.EXACTLY_ONCE`
    private transient ListState<Map<String, StarRocksSinkBufferEntity>> checkpointedState;
 
    public StarRocksDynamicSinkFunction(StarRocksSinkOptions sinkOptions, TableSchema schema, StarRocksIRowTransformer<T> rowTransformer) {
        this.sinkManager = new StarRocksSinkManager(sinkOptions, schema);
        rowTransformer.setTableSchema(schema);
        this.serializer = StarRocksSerializerFactory.createSerializer(sinkOptions, schema.getFieldNames());
        this.rowTransformer = rowTransformer;
        this.sinkOptions = sinkOptions;
    }
 
    public StarRocksDynamicSinkFunction(StarRocksSinkOptions sinkOptions) {
        this.sinkManager = new StarRocksSinkManager(sinkOptions, null);
        this.sinkOptions = sinkOptions;
    }
 
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        sinkManager.setRuntimeContext(getRuntimeContext());
        totalInvokeRows = getRuntimeContext().getMetricGroup().counter(COUNTER_INVOKE_ROWS);
        totalInvokeRowsTime = getRuntimeContext().getMetricGroup().counter(COUNTER_INVOKE_ROWS_COST_TIME);
        if (null != rowTransformer) {
            rowTransformer.setRuntimeContext(getRuntimeContext());
        }
        sinkManager.startScheduler();
        sinkManager.startAsyncFlushing();
    }

    @Override
    public synchronized void invoke(T value, Context context) throws Exception {
        long start = System.nanoTime();
        if (StarRocksSinkSemantic.EXACTLY_ONCE.equals(sinkOptions.getSemantic())) {
            flushPreviousState();
        }
        if (null == serializer) {
            if (value instanceof StarRocksSinkRowDataWithMeta) {
                StarRocksSinkRowDataWithMeta data = (StarRocksSinkRowDataWithMeta)value;
                if (Strings.isNullOrEmpty(data.getDatabase()) || Strings.isNullOrEmpty(data.getTable()) || null == data.getDataRows()) {
                    LOG.warn(String.format("json row data not fullfilled. {database: %s, table: %s, dataRows: %s}", data.getDatabase(), data.getTable(), data.getDataRows()));
                    return;
                }
                sinkManager.writeRecords(data.getDatabase(), data.getTable(), data.getDataRows());
                return;
            }
            // raw data sink
            sinkManager.writeRecords(sinkOptions.getDatabaseName(), sinkOptions.getTableName(), (String) value);
            totalInvokeRows.inc(1);
            totalInvokeRowsTime.inc(System.nanoTime() - start);
            return;
        }
        if (value instanceof NestedRowData) {
            final int headerSize = 256;
            NestedRowData ddlData = (NestedRowData) value;
            if (ddlData.getSegments().length != 1 || ddlData.getSegments()[0].size() < headerSize) {
                return;
            }
            int totalSize = ddlData.getSegments()[0].size();
            byte[] data = new byte[totalSize - headerSize];
            ddlData.getSegments()[0].get(headerSize, data);
            Map<String, String> ddlMap = InstantiationUtil.deserializeObject(data, HashMap.class.getClassLoader());
            if (null == ddlMap 
                || "true".equals(ddlMap.get("snapshot"))
                || Strings.isNullOrEmpty(ddlMap.get("ddl"))
                || Strings.isNullOrEmpty(ddlMap.get("databaseName"))) {
                return;
            }
            Statement stmt = CCJSqlParserUtil.parse(ddlMap.get("ddl"));
            if (stmt instanceof Truncate) {
                Truncate truncate = (Truncate) stmt;
                if (!sinkOptions.getTableName().equalsIgnoreCase(truncate.getTable().getName())) {
                    return;
                }
                // TODO: add ddl to queue
            } else if (stmt instanceof Alter) {
                Alter alter = (Alter) stmt;
            }
        }
        if (value instanceof RowData) {
            if (RowKind.UPDATE_BEFORE.equals(((RowData)value).getRowKind())) {
                // do not need update_before, cauz an update action happened on the primary keys will be separated into `delete` and `create`
                return;
            }
            if (!sinkOptions.supportUpsertDelete() && RowKind.DELETE.equals(((RowData)value).getRowKind())) {
                // let go the UPDATE_AFTER and INSERT rows for tables who have a group of `unique` or `duplicate` keys.
                return;
            }
        }
        sinkManager.writeRecords(
            sinkOptions.getDatabaseName(),
            sinkOptions.getTableName(),
            serializer.serialize(rowTransformer.transform(value, sinkOptions.supportUpsertDelete()))
        );
        totalInvokeRows.inc(1);
        totalInvokeRowsTime.inc(System.nanoTime() - start);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        if (!StarRocksSinkSemantic.EXACTLY_ONCE.equals(sinkOptions.getSemantic())) {
            return;
        }
        ListStateDescriptor<Map<String, StarRocksSinkBufferEntity>> descriptor =
            new ListStateDescriptor<>(
                "buffered-rows",
                TypeInformation.of(new TypeHint<Map<String, StarRocksSinkBufferEntity>>(){})
            );
        checkpointedState = context.getOperatorStateStore().getListState(descriptor);
    }

    @Override
    public synchronized void snapshotState(FunctionSnapshotContext context) throws Exception {
        if (StarRocksSinkSemantic.EXACTLY_ONCE.equals(sinkOptions.getSemantic())) {
            flushPreviousState();
            // save state
            checkpointedState.add(sinkManager.getBufferedBatchMap());
            return;
        }
        sinkManager.flush(null, true);
    }

    @Override
    public synchronized void close() throws Exception {
        super.close();
        if (StarRocksSinkSemantic.EXACTLY_ONCE.equals(sinkOptions.getSemantic())) {
            flushPreviousState();
        }
        sinkManager.close();
    }

    private void flushPreviousState() throws Exception {
        // flush the batch saved at the previous checkpoint
        for (Map<String, StarRocksSinkBufferEntity> state : checkpointedState.get()) {
            sinkManager.setBufferedBatchMap(state);
            sinkManager.flush(null, true);
        }
        checkpointedState.clear();
    }
}