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
package org.apache.phoenix.expression.function;

import java.sql.SQLException;
import java.util.List;

import org.apache.hadoop.hbase.io.ImmutableBytesWritable;

import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.parse.FunctionParseNode.Argument;
import org.apache.phoenix.parse.FunctionParseNode.BuiltInFunction;
import org.apache.phoenix.schema.ColumnModifier;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.StringUtil;


/**
 * Implementation of the Trim(<string>) build-in function. It removes from both end of <string>
 * space character and other function bytes in single byte utf8 characters set.
 * 
 * 
 * @since 0.1
 */
@BuiltInFunction(name=TrimFunction.NAME, args={
    @Argument(allowedTypes={PDataType.VARCHAR})} )
public class TrimFunction extends ScalarFunction {
    public static final String NAME = "TRIM";

    private Integer byteSize;

    public TrimFunction() { }

    public TrimFunction(List<Expression> children) throws SQLException {
        super(children);
        if (getStringExpression().getDataType().isFixedWidth()) {
            byteSize = getStringExpression().getByteSize();
        }
    }

    private Expression getStringExpression() {
        return children.get(0);
    }

    @Override
    public ColumnModifier getColumnModifier() {
        return children.get(0).getColumnModifier();
    }    

    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
        if (!getStringExpression().evaluate(tuple, ptr)) {
            return false;
        }
        if (ptr.getLength() == 0) {
            ptr.set(ByteUtil.EMPTY_BYTE_ARRAY);
            return true;
        }
        byte[] string = ptr.get();
        int offset = ptr.getOffset();
        int length = ptr.getLength();
        
        ColumnModifier columnModifier = getColumnModifier();
        int end = StringUtil.getFirstNonBlankCharIdxFromEnd(string, offset, length, columnModifier);
        if (end == offset - 1) {
            ptr.set(ByteUtil.EMPTY_BYTE_ARRAY);
            return true; 
        }
        int head = StringUtil.getFirstNonBlankCharIdxFromStart(string, offset, length, columnModifier);
        ptr.set(string, head, end - head + 1);
        return true;
    }

    @Override
    public Integer getByteSize() {
        return byteSize;
    }

    @Override
    public PDataType getDataType() {
        return PDataType.VARCHAR;
    }

    @Override
    public String getName() {
        return NAME;
    }

}
