/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.iceberg.sink.trick;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.flink.FlinkSchemaUtil;
import org.apache.iceberg.flink.RowDataWrapper;
import org.apache.iceberg.flink.data.RowDataProjection;
import org.apache.iceberg.io.FileAppenderFactory;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.types.TypeUtil;

import java.io.IOException;
import java.util.List;

public abstract class BaseDeltaTaskWriter extends BaseTaskWriter<RowData> {

    private final Schema schema;
    private final Schema deleteSchema;
    private final RowDataWrapper wrapper;
    private final RowDataWrapper keyWrapper;
    private final RowDataProjection keyProjection;
    private final boolean upsert;

    public BaseDeltaTaskWriter(PartitionSpec spec,
            FileFormat format,
            FileAppenderFactory<RowData> appenderFactory,
            OutputFileFactory fileFactory,
            FileIO io,
            long targetFileSize,
            Schema schema,
            RowType flinkSchema,
            List<Integer> equalityFieldIds,
            boolean upsert) {
        super(spec, format, appenderFactory, fileFactory, io, targetFileSize);
        this.schema = schema;
        this.deleteSchema = TypeUtil.select(schema, Sets.newHashSet(equalityFieldIds));
        this.wrapper = new RowDataWrapper(flinkSchema, schema.asStruct());
        this.upsert = upsert;
        this.keyWrapper = new RowDataWrapper(FlinkSchemaUtil.convert(deleteSchema), deleteSchema.asStruct());
        this.keyProjection = RowDataProjection.create(schema, deleteSchema);
    }

    public abstract BaseDeltaTaskWriter.RowDataDeltaWriter route(RowData row);

    public RowDataWrapper wrapper() {
        return wrapper;
    }

    @Override
    public void write(RowData row) throws IOException {
        BaseDeltaTaskWriter.RowDataDeltaWriter writer = route(row);

        switch (row.getRowKind()) {
            case INSERT:
            case UPDATE_AFTER:
                if (upsert) {
                    writer.deleteKey(keyProjection.wrap(row));
                }
                writer.write(row);
                break;

            case UPDATE_BEFORE:
                if (upsert) {
                    break; // UPDATE_BEFORE is not necessary for UPDATE, we do nothing to prevent delete one row twice
                }
                writer.delete(row);
                break;
            case DELETE:
                if (upsert) { // https://github.com/apache/iceberg/pull/6753/files
                    writer.deleteKey(keyProjection.wrap(row));
                } else {
                    writer.delete(row);
                }
                break;

            default:
                throw new UnsupportedOperationException("Unknown row kind: " + row.getRowKind());
        }
    }

    protected class RowDataDeltaWriter extends BaseEqualityDeltaWriter {

        public RowDataDeltaWriter(PartitionKey partition) {
            super(partition, schema, deleteSchema);
        }

        @Override
        protected StructLike asStructLike(RowData data) {
            return wrapper.wrap(data);
        }

        @Override
        protected StructLike asStructLikeKey(RowData data) {
            return keyWrapper.wrap(data);
        }
    }
}
