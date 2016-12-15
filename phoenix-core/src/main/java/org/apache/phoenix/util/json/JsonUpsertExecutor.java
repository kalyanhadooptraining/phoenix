/*
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
package org.apache.phoenix.util.json;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Nullable;

import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PTimestamp;
import org.apache.phoenix.util.ColumnInfo;
import org.apache.phoenix.util.DateUtil;
import org.apache.phoenix.util.UpsertExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;

/** {@link UpsertExecutor} over {@link Map} objects, as parsed from JSON. */
public class JsonUpsertExecutor extends UpsertExecutor<Map<?, ?>, Object> {

    protected static final Logger LOG = LoggerFactory.getLogger(JsonUpsertExecutor.class);

    /** Testing constructor. Do not use in prod. */
    @VisibleForTesting
    protected JsonUpsertExecutor(Connection conn, List<ColumnInfo> columnInfoList,
            PreparedStatement stmt, UpsertListener<Map<?, ?>> upsertListener) {
        super(conn, columnInfoList, stmt, upsertListener);
        finishInit();
    }

    public JsonUpsertExecutor(Connection conn, String tableName, List<ColumnInfo> columnInfoList,
            UpsertExecutor.UpsertListener<Map<?, ?>> upsertListener) {
        super(conn, tableName, columnInfoList, upsertListener);
        finishInit();
    }

    @Override
    protected void execute(Map<?, ?> record) {
        int fieldIndex = 0;
        String colName = null;
        try {
            if (record.size() < conversionFunctions.size()) {
                String message = String.format("JSON record does not have enough values (has %d, but needs %d)",
                        record.size(), conversionFunctions.size());
                throw new IllegalArgumentException(message);
            }
            for (fieldIndex = 0; fieldIndex < conversionFunctions.size(); fieldIndex++) {
                colName = columnInfos.get(fieldIndex).getColumnName();
                Object sqlValue = conversionFunctions.get(fieldIndex).apply(record.get(colName));
                if (sqlValue != null) {
                    preparedStatement.setObject(fieldIndex + 1, sqlValue);
                } else {
                    preparedStatement.setNull(fieldIndex + 1, dataTypes.get(fieldIndex).getSqlType());
                }
            }
            preparedStatement.execute();
            upsertListener.upsertDone(++upsertCount);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                // Even though this is an error we only log it with debug logging because we're notifying the
                // listener, and it can do its own logging if needed
                LOG.debug("Error on record " + record + ", fieldIndex " + fieldIndex + ", colName " + colName, e);
            }
            upsertListener.errorOnRecord(record, new Exception("fieldIndex: " + fieldIndex + ", colName " + colName, e));
        }
    }

    @Override
    public void close() throws IOException {
        try {
            preparedStatement.close();
        } catch (SQLException e) {
            // An exception while closing the prepared statement is most likely a sign of a real problem, so we don't
            // want to hide it with closeQuietly or something similar
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Function<Object, Object> createConversionFunction(PDataType dataType) {
        if (dataType.isArrayType()) {
            return new ArrayDatatypeConversionFunction(
                    new ObjectToArrayConverter(
                            conn,
                            PDataType.fromTypeId(dataType.getSqlType() - PDataType.ARRAY_TYPE_BASE)));
        } else {
            return new SimpleDatatypeConversionFunction(dataType, this.conn);
        }
    }

    /**
     * Performs typed conversion from String values to a given column value type.
     */
    static class SimpleDatatypeConversionFunction implements Function<Object, Object> {

        private final PDataType dataType;
        private final DateUtil.DateTimeParser dateTimeParser;

        SimpleDatatypeConversionFunction(PDataType dataType, Connection conn) {
            Properties props;
            try {
                props = conn.getClientInfo();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            this.dataType = dataType;
            if (dataType.isCoercibleTo(PTimestamp.INSTANCE)) {
                // TODO: move to DateUtil
                String dateFormat;
                int dateSqlType = dataType.getResultSetSqlType();
                if (dateSqlType == Types.DATE) {
                    dateFormat = props.getProperty(QueryServices.DATE_FORMAT_ATTRIB,
                            DateUtil.DEFAULT_DATE_FORMAT);
                } else if (dateSqlType == Types.TIME) {
                    dateFormat = props.getProperty(QueryServices.TIME_FORMAT_ATTRIB,
                            DateUtil.DEFAULT_TIME_FORMAT);
                } else {
                    dateFormat = props.getProperty(QueryServices.TIMESTAMP_FORMAT_ATTRIB,
                            DateUtil.DEFAULT_TIMESTAMP_FORMAT);
                }
                String timeZoneId = props.getProperty(QueryServices.DATE_FORMAT_TIMEZONE_ATTRIB,
                        QueryServicesOptions.DEFAULT_DATE_FORMAT_TIMEZONE);
                this.dateTimeParser = DateUtil.getDateTimeParser(dateFormat, dataType, timeZoneId);
            } else {
                this.dateTimeParser = null;
            }
        }

        @Nullable
        @Override
        public Object apply(@Nullable Object input) {
            if (input == null) {
                return null;
            }
            if (dateTimeParser != null && input instanceof String) {
                final String s = (String) input;
                long epochTime = dateTimeParser.parseDateTime(s);
                byte[] byteValue = new byte[dataType.getByteSize()];
                dataType.getCodec().encodeLong(epochTime, byteValue, 0);
                return dataType.toObject(byteValue);
            }
            return dataType.toObject(input, dataType);
        }
    }

    /**
     * Converts string representations of arrays into Phoenix arrays of the correct type.
     */
    private static class ArrayDatatypeConversionFunction implements Function<Object, Object> {

        private final ObjectToArrayConverter arrayConverter;

        private ArrayDatatypeConversionFunction(ObjectToArrayConverter arrayConverter) {
            this.arrayConverter = arrayConverter;
        }

        @Nullable
        @Override
        public Object apply(@Nullable Object input) {
            try {
                return arrayConverter.toArray(input);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
