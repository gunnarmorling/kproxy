/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.strimzi.kproxy.codec;

import org.apache.kafka.common.protocol.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBuf;

/**
 * A frame that has been decoded (as opposed to an {@link OpaqueFrame}).
 * @param <H>
 */
public abstract class DecodedFrame<H extends ApiMessage> implements Frame {
    private static final Logger LOGGER = LogManager.getLogger(DecodedFrame.class);

    protected final H header;
    protected final ApiMessage body;
    protected final short apiVersion;
    private int headerAndBodyEncodedLength;
    private ObjectSerializationCache serializationCache;


    public DecodedFrame(short apiVersion, H header, ApiMessage body) {
        this.header = header;
        this.apiVersion = apiVersion;
        this.body = body;
        this.headerAndBodyEncodedLength = -1;
    }

    protected abstract short headerVersion();

    public H header() {
        return header;
    }

    public ApiMessage body() {
        return body;
    }

    public ApiKeys apiKey() {
        return ApiKeys.forId(body.apiKey());
    }

    public short apiVersion() {
        return apiVersion;
    }

    @Override
    public final int estimateEncodedSize() {
        if (headerAndBodyEncodedLength != -1) {
            assert serializationCache != null;
            return headerAndBodyEncodedLength + Integer.BYTES;
        }
        var headerVersion = headerVersion();
        MessageSizeAccumulator sizer = new MessageSizeAccumulator();
        ObjectSerializationCache cache = new ObjectSerializationCache();
        header().addSize(sizer, cache, headerVersion);
        body().addSize(sizer, cache, apiVersion());
        headerAndBodyEncodedLength = sizer.totalSize();
        serializationCache = cache;
        return headerAndBodyEncodedLength + Integer.BYTES;
    }

    public final void encode(ByteBuf out) {
        int length = headerAndBodyEncodedLength;
        if (length < 0) {
            LOGGER.warn("Encoding estimation should happen before encoding, if possible");
            length = estimateEncodedSize();
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Writing {} with 4 byte length ({}) plus bytes of header {}, and body {} to {}",
                    getClass().getSimpleName(), length, header, body, out);
        }
        assert out.writableBytes() == estimateEncodedSize();
        assert out.nioBufferCount() == 1;
        final ByteBufferAccessor writable = new ByteBufferAccessor(out.nioBuffer(out.writerIndex(), out.writableBytes()));
        writable.writeInt(length);
        header.write(writable, serializationCache, headerVersion());
        body.write(writable, serializationCache, apiVersion());
        final int written = writable.buffer().position();
        assert written == estimateEncodedSize();
        out.writerIndex(out.writerIndex() + written);
        // TODO we should release here whatever ByteBuf has been used in the MemoryRecords on the interceptor
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" +
                ApiKeys.forId(apiVersion) + "(" + apiVersion + ")v" + apiVersion +
                ", header=" + header +
                ", body=" + body +
                ')';
    }
}
