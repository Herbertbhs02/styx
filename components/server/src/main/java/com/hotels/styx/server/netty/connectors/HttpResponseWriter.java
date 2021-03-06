/*
  Copyright (C) 2013-2018 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx.server.netty.connectors;

import com.hotels.styx.api.Buffers;
import com.hotels.styx.api.LiveHttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Subscriber;
import rx.Subscription;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.handler.codec.http.HttpHeaders.setTransferEncodingChunked;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static java.util.Objects.requireNonNull;
import static rx.RxReactiveStreams.toObservable;

/**
 * Netty HTTP response writer.
 */
class HttpResponseWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpResponseWriter.class);
    private final AtomicLong writeOps = new AtomicLong(0);
    private final AtomicLong contentBytesWritten = new AtomicLong(0);
    private final AtomicLong writeOpsAcked = new AtomicLong(0);
    private final AtomicLong contentBytesAcked = new AtomicLong(0);
    private final AtomicBoolean contentCompleted = new AtomicBoolean(false);

    private final ChannelHandlerContext ctx;
    private final ResponseTranslator responseTranslator;

    HttpResponseWriter(ChannelHandlerContext ctx) {
        this(ctx, new StyxToNettyResponseTranslator());
    }

    HttpResponseWriter(ChannelHandlerContext ctx, ResponseTranslator responseTranslator) {
        this.ctx = requireNonNull(ctx);
        this.responseTranslator = requireNonNull(responseTranslator);
    }

    // CHECKSTYLE:OFF
    public CompletableFuture<Void> write(LiveHttpResponse response) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            writeHeaders(response).addListener((ChannelFutureListener) writeOp -> {
                if (writeOp.isSuccess()) {
                    writeOpsAcked.incrementAndGet();
                } else {
                    LOGGER.warn("Unable to send response headers. Written content bytes {}/{} (ackd/sent). Write events {}/{} (ackd/writes). Exception={}",
                            new Object[]{
                                    contentBytesAcked.get(),
                                    contentBytesWritten.get(),
                                    writeOpsAcked.get(),
                                    writeOps.get(),
                                    response,
                                    writeOp.cause()});
                    future.completeExceptionally(writeOp.cause());
                }
            });

            Subscription subscriber = toObservable(response.body()).map(Buffers::toByteBuf).subscribe(new Subscriber<ByteBuf>() {
                @Override
                public void onStart() {
                    request(1);
                }

                @Override
                public void onCompleted() {
                    if (!future.isDone()) {
                        nettyWriteAndFlush(EMPTY_LAST_CONTENT).addListener((ChannelFutureListener) this::onWriteEmptyLastChunkOutcome);
                        contentCompleted.set(true);
                        completeIfAllSent(future);
                    }
                }

                @Override
                public void onError(Throwable cause) {
                    LOGGER.warn("Content observable error. Written content bytes {}/{} (ackd/sent). Write events {}/{} (ackd/writes). Exception={}",
                            new Object[]{
                                    contentBytesAcked.get(),
                                    contentBytesWritten.get(),
                                    writeOpsAcked.get(),
                                    writeOps.get(),
                                    cause
                    });
                    future.completeExceptionally(cause);
                }

                @Override
                public void onNext(ByteBuf byteBuf) {
                    if (future.isDone()) {
                        byteBuf.release();
                    } else {
                        long bufSize = (long) byteBuf.readableBytes();
                        contentBytesWritten.addAndGet(bufSize);
                        nettyWriteAndFlush(new DefaultHttpContent(byteBuf))
                                .addListener(it -> onWriteOutcome((ChannelFuture) it, bufSize));
                    }
                }

                private void onWriteOutcome(ChannelFuture writeOp, long bufSize) {
                    if (writeOp.isSuccess()) {
                        contentBytesAcked.addAndGet(bufSize);
                        writeOpsAcked.incrementAndGet();
                        request(1);
                        completeIfAllSent(future);
                    } else if (!future.isDone()) {
                        // Suppress messages if future has already failed, or completed for other reason:
                        unsubscribe();
                        LOGGER.warn("Write error. Written content bytes {}/{} (ackd/sent). Write events {}/{} (ackd/writes), Exception={}", new Object[]{
                                contentBytesAcked.get(),
                                contentBytesWritten.get(),
                                writeOpsAcked.get(),
                                writeOps.get(),
                                response,
                                writeOp.cause()});
                        future.completeExceptionally(writeOp.cause());
                    }
                }

                private void onWriteEmptyLastChunkOutcome(ChannelFuture writeOp) {
                    writeOpsAcked.incrementAndGet();
                    completeIfAllSent(future);
                    unsubscribe();
                }

            });

            future.handle((ignore, cause) -> {
                if (future.isCompletedExceptionally() && cause instanceof CancellationException) {
                    subscriber.unsubscribe();
                }
                return null;
            });

            return future;
        } catch (Throwable cause) {
            LOGGER.warn("Failed to convert response headers. response={}, Cause={}", new Object[]{response, cause});
            toObservable(response.body()).forEach(it -> Buffers.toByteBuf(it).release());
            future.completeExceptionally(cause);
            return future;
        }
    }
    // CHECKSTYLE:ON


    private void completeIfAllSent(CompletableFuture<Void> future) {
        if (contentCompleted.get() && writeOps.get() == writeOpsAcked.get()) {
            future.complete(null);
        }
    }

    private ChannelFuture writeHeaders(LiveHttpResponse response) {
        io.netty.handler.codec.http.HttpResponse nettyResponse = responseTranslator.toNettyResponse(response);
        if (!(response.contentLength().isPresent() || response.chunked())) {
            setTransferEncodingChunked(nettyResponse);
        }

        return nettyWriteAndFlush(nettyResponse);
    }

    private ChannelFuture nettyWriteAndFlush(Object msg) {
        writeOps.incrementAndGet();
        return ctx.writeAndFlush(msg);
    }
}
