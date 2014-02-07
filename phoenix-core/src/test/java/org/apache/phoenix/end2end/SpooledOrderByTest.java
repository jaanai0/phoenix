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
package org.apache.phoenix.end2end;

import java.util.Map;

import org.junit.BeforeClass;

import com.google.common.collect.Maps;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.ReadOnlyProps;

public class SpooledOrderByTest extends OrderByTest {

    @BeforeClass
    public static void doSetup() throws Exception {
        Map<String,String> props = Maps.newHashMapWithExpectedSize(3);
        props.put(QueryServices.SPOOL_THRESHOLD_BYTES_ATTRIB, Integer.toString(100));
        // Must update config before starting server
        startServer(getUrl(), new ReadOnlyProps(props.entrySet().iterator()));
    }

}
