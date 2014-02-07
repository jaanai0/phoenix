/*
 * Copyright 2014 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.compile;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.cache.ServerCacheClient.ServerCache;
import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.coprocessor.UngroupedAggregateRegionObserver;
import org.apache.phoenix.execute.AggregatePlan;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixParameterMetaData;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.parse.SelectStatement;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.ColumnRef;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnFamily;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.util.ScanUtil;

import com.google.common.collect.Lists;


/**
 * 
 * Class that compiles plan to update data values after a DDL command
 * executes.
 *
 * TODO: get rid of this ugly code and just go through the standard APIs.
 * The only time we may still need this is to manage updating the empty
 * key value, as we sometimes need to "go back through time" to adjust
 * this.
 * 
 * @since 0.1
 */
public class PostDDLCompiler {
    private final PhoenixConnection connection;

    public PostDDLCompiler(PhoenixConnection connection) {
        this.connection = connection;
    }

    public MutationPlan compile(final List<TableRef> tableRefs, final byte[] emptyCF, final byte[] projectCF, final List<PColumn> deleteList,
            final long timestamp) throws SQLException {
        
        return new MutationPlan() {
            
            @Override
            public PhoenixConnection getConnection() {
                return connection;
            }
            
            @Override
            public ParameterMetaData getParameterMetaData() {
                return PhoenixParameterMetaData.EMPTY_PARAMETER_META_DATA;
            }
            
            @Override
            public ExplainPlan getExplainPlan() throws SQLException {
                return ExplainPlan.EMPTY_PLAN;
            }
            
            @Override
            public MutationState execute() throws SQLException {
                if (tableRefs.isEmpty()) {
                    return null;
                }
                boolean wasAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(true);
                    SQLException sqlE = null;
                    if (deleteList == null && emptyCF == null) {
                        return new MutationState(0, connection);
                    }
                    /*
                     * Handles:
                     * 1) deletion of all rows for a DROP TABLE and subsequently deletion of all rows for a DROP INDEX;
                     * 2) deletion of all column values for a ALTER TABLE DROP COLUMN
                     * 3) updating the necessary rows to have an empty KV
                     */
                    long totalMutationCount = 0;
                    for (final TableRef tableRef : tableRefs) {
                        Scan scan = new Scan();
                        scan.setAttribute(UngroupedAggregateRegionObserver.UNGROUPED_AGG, QueryConstants.TRUE);
                        SelectStatement select = SelectStatement.COUNT_ONE;
                        // We need to use this tableRef
                        ColumnResolver resolver = new ColumnResolver() {
                            @Override
                            public List<TableRef> getTables() {
                                return Collections.singletonList(tableRef);
                            }
                            @Override
                            public ColumnRef resolveColumn(String schemaName, String tableName, String colName) throws SQLException {
                                PColumn column = tableName != null
                                        ? tableRef.getTable().getColumnFamily(tableName).getColumn(colName)
                                        : tableRef.getTable().getColumn(colName);
                                return new ColumnRef(tableRef, column.getPosition());
                            }
                        };
                        StatementContext context = new StatementContext(new PhoenixStatement(connection), resolver, Collections.<Object>emptyList(), scan);
                        ScanUtil.setTimeRange(scan, timestamp);
                        if (emptyCF != null) {
                            scan.setAttribute(UngroupedAggregateRegionObserver.EMPTY_CF, emptyCF);
                        }
                        ServerCache cache = null;
                        try {
                            if (deleteList != null) {
                                if (deleteList.isEmpty()) {
                                    scan.setAttribute(UngroupedAggregateRegionObserver.DELETE_AGG, QueryConstants.TRUE);
                                    // In the case of a row deletion, add index metadata so mutable secondary indexing works
                                    /* TODO
                                    ImmutableBytesWritable ptr = context.getTempPtr();
                                    tableRef.getTable().getIndexMaintainers(ptr);
                                    if (ptr.getLength() > 0) {
                                        IndexMetaDataCacheClient client = new IndexMetaDataCacheClient(connection, tableRef);
                                        cache = client.addIndexMetadataCache(context.getScanRanges(), ptr);
                                        byte[] uuidValue = cache.getId();
                                        scan.setAttribute(PhoenixIndexCodec.INDEX_UUID, uuidValue);
                                    }
                                    */
                                } else {
                                    // In the case of the empty key value column family changing, do not send the index
                                    // metadata, as we're currently managing this from the client. It's possible for the
                                    // data empty column family to stay the same, while the index empty column family
                                    // changes.
                                    PColumn column = deleteList.get(0);
                                    if (emptyCF == null) {
                                        scan.addColumn(column.getFamilyName().getBytes(), column.getName().getBytes());
                                    }
                                    scan.setAttribute(UngroupedAggregateRegionObserver.DELETE_CF, column.getFamilyName().getBytes());
                                    scan.setAttribute(UngroupedAggregateRegionObserver.DELETE_CQ, column.getName().getBytes());
                                }
                            }
                            List<byte[]> columnFamilies = Lists.newArrayListWithExpectedSize(tableRef.getTable().getColumnFamilies().size());
                            if (projectCF == null) {
                                for (PColumnFamily family : tableRef.getTable().getColumnFamilies()) {
                                    columnFamilies.add(family.getName().getBytes());
                                }
                            } else {
                                columnFamilies.add(projectCF);
                            }
                            // Need to project all column families into the scan, since we haven't yet created our empty key value
                            RowProjector projector = ProjectionCompiler.compile(context, SelectStatement.COUNT_ONE, GroupBy.EMPTY_GROUP_BY);
                            // Explicitly project these column families and don't project the empty key value,
                            // since at this point we haven't added the empty key value everywhere.
                            if (columnFamilies != null) {
                                scan.getFamilyMap().clear();
                                for (byte[] family : columnFamilies) {
                                    scan.addFamily(family);
                                }
                                projector = new RowProjector(projector,false);
                            }
                            WhereCompiler.compile(context, select); // Push where clause into scan
                            QueryPlan plan = new AggregatePlan(context, select, tableRef, projector, null, OrderBy.EMPTY_ORDER_BY, null, GroupBy.EMPTY_GROUP_BY, null);
                            ResultIterator iterator = plan.iterator();
                            try {
                                Tuple row = iterator.next();
                                ImmutableBytesWritable ptr = context.getTempPtr();
                                totalMutationCount += (Long)projector.getColumnProjector(0).getValue(row, PDataType.LONG, ptr);
                            } catch (SQLException e) {
                                sqlE = e;
                            } finally {
                                try {
                                    iterator.close();
                                } catch (SQLException e) {
                                    if (sqlE == null) {
                                        sqlE = e;
                                    } else {
                                        sqlE.setNextException(e);
                                    }
                                } finally {
                                    if (sqlE != null) {
                                        throw sqlE;
                                    }
                                }
                            }
                        } finally {
                            if (cache != null) { // Remove server cache if there is one
                                cache.close();
                            }
                        }
                        
                    }
                    final long count = totalMutationCount;
                    return new MutationState(1, connection) {
                        @Override
                        public long getUpdateCount() {
                            return count;
                        }
                    };
                } finally {
                    if (!wasAutoCommit) connection.setAutoCommit(wasAutoCommit);
                }
            }
        };
    }
}
