package com.starrocks.connector.flink.table.source;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;

import com.starrocks.connector.flink.table.source.struct.ColunmRichInfo;

import com.starrocks.connector.flink.table.source.struct.QueryBeXTablets;
import com.starrocks.connector.flink.table.source.struct.QueryInfo;
import com.starrocks.connector.flink.table.source.struct.SelectColumn;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StarRocksDynamicLookupFunction extends TableFunction<RowData> {
    
    private static final Logger LOG = LoggerFactory.getLogger(StarRocksDynamicLookupFunction.class);
    
    private final ColunmRichInfo[] filterRichInfos;
    private final StarRocksSourceOptions sourceOptions;
    private QueryInfo queryInfo;
    private final SelectColumn[] selectColumns;
    private final List<ColunmRichInfo> columnRichInfos;
    
    private final long cacheMaxSize;
    private final long cacheExpireMs;
    private final int maxRetryTimes;

    // cache for lookup data
    private Map<Row, List<RowData>> cacheMap;

    private transient long nextLoadTime;

    public StarRocksDynamicLookupFunction(StarRocksSourceOptions sourceOptions, 
                                          ColunmRichInfo[] filterRichInfos, 
                                          List<ColunmRichInfo> columnRichInfos,
                                          SelectColumn[] selectColumns
                                          ) {
        this.sourceOptions = sourceOptions;
        this.filterRichInfos = filterRichInfos;
        this.columnRichInfos = columnRichInfos;
        this.selectColumns = selectColumns;

        this.cacheMaxSize = sourceOptions.getLookupCacheMaxRows();
        this.cacheExpireMs = sourceOptions.getLookupCacheTTL();
        this.maxRetryTimes = sourceOptions.getLookupMaxRetries();
        
        this.cacheMap = new HashMap<>();
        this.nextLoadTime = -1L;
    }
    
    @Override
    public void open(FunctionContext context) throws Exception {
        super.open(context);
    }

    public void eval(Object... keys) {
        reloadData();
        Row keyRow = Row.of(keys);
        List<RowData> curList = cacheMap.get(keyRow);
        if (curList != null) {
            curList.parallelStream().forEach(data -> {
                collect(data);
            });
        }
    }

    private void reloadData() {
        if (nextLoadTime > System.currentTimeMillis()) {
            return;
        }
        if (nextLoadTime > 0) {
            LOG.info("Lookup join cache has expired after {} (ms), reloading", this.cacheExpireMs);
        } else {
            LOG.info("Populating lookup join cache");
        }
        cacheMap.clear();
        
        StringBuffer sqlSb = new StringBuffer("select * from "); 
        sqlSb.append("`" + sourceOptions.getDatabaseName() + "`");
        sqlSb.append(".");
        sqlSb.append("`" + sourceOptions.getTableName() + "`");
        LOG.info("LookUpFunction SQL [{}]", sqlSb.toString());
        this.queryInfo = StarRocksSourceCommonFunc.getQueryInfo(this.sourceOptions, sqlSb.toString());
        List<List<QueryBeXTablets>> lists = StarRocksSourceCommonFunc.splitQueryBeXTablets(1, queryInfo);
        cacheMap = lists.get(0).parallelStream().flatMap(beXTablets -> {
            StarRocksSourceBeReader beReader = new StarRocksSourceBeReader(beXTablets.getBeNode(), 
                                                                           columnRichInfos, 
                                                                           selectColumns, 
                                                                           sourceOptions);
            beReader.openScanner(beXTablets.getTabletIds(), queryInfo.getQueryPlan().getOpaqued_query_plan(), sourceOptions);
            beReader.startToRead();
            List<RowData> tmpDataList = new ArrayList<>();
            while (beReader.hasNext()) {
                RowData row = beReader.getNext();
                tmpDataList.add(row);
            }
            return tmpDataList.stream();
        }).collect(Collectors.groupingBy(row -> {
            GenericRowData gRowData = (GenericRowData)row;
            Object keyObj[] = new Object[filterRichInfos.length];
            for (int i = 0; i < filterRichInfos.length; i ++) {
                keyObj[i] = gRowData.getField(filterRichInfos[i].getColunmIndexInSchema());
            }
            return Row.of(keyObj);
        }));
        nextLoadTime = System.currentTimeMillis() + this.cacheExpireMs;
    }

    @Override
    public void close() throws Exception {
        super.close();
    }
}
