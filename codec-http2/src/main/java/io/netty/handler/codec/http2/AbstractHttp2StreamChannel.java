/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.VoidChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http2.Http2FrameCodec.DefaultHttp2FrameStream;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import static io.netty.handler.codec.http2.Http2CodecUtil.isStreamIdValid;
import static java.lang.Math.min;

abstract class AbstractHttp2StreamChannel extends DefaultAttributeMap implements Http2StreamChannel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractHttp2StreamChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    /**
     * Number of bytes to consider non-payload messages. 9 is arbitrary, but also the minimum size of an HTTP/2 frame.
     * Primarily is non-zero.
     */
    private static final int MIN_HTTP2_FRAME_SIZE = 9;

    /**
     * Returns the flow-control size for DATA frames, and {@codec MIN_HTTP2_FRAME_SIZE} for all other frames.
     */
    private static final class FlowControlledFrameSizeEstimator implements MessageSizeEstimator {

        static final FlowControlledFrameSizeEstimator INSTANCE = new FlowControlledFrameSizeEstimator();

        private static final Handle HANDLE_INSTANCE = new Handle() {
            @Override
            public int size(Object msg) {
                return msg instanceof Http2DataFrame ?
                        // Guard against overflow.
                        (int) min(Integer.MAX_VALUE, ((Http2DataFrame) msg).initialFlowControlledBytes() +
                                (long) MIN_HTTP2_FRAME_SIZE) : MIN_HTTP2_FRAME_SIZE;
            }
        };

        @Override
        public Handle newHandle() {
            return HANDLE_INSTANCE;
        }
    }

    /**
     * The current status of the read-processing for a {@link AbstractHttp2StreamChannel}.
     */
    private enum ReadStatus {
        /**
         * No read in progress and no read was requested (yet)
         */
        IDLE,

        /**
         * Reading in progress
         */
        IN_PROGRESS,

        /**
         * A read operation was requested.
         */
        REQUESTED
    }

    private final AbstractHttp2StreamChannel.Http2StreamChannelConfig config = new Http2StreamChannelConfig(this);
    private final AbstractHttp2StreamChannel.Http2ChannelUnsafe unsafe = new Http2ChannelUnsafe();
    private final ChannelId channelId;
    private final ChannelPipeline pipeline;
    private final DefaultHttp2FrameStream stream;
    private final ChannelPromise closePromise;

    private volatile boolean registered;
    // We start with the writability of the channel when creating the StreamChannel.
    private volatile boolean writable;

    private boolean outboundClosed;

    /**
     * This variable represents if a read is in progress for the current channel or was requested.
     * Note that depending upon the {@link RecvByteBufAllocator} behavior a read may extend beyond the
     * {@link Http2ChannelUnsafe#beginRead()} method scope. The {@link Http2ChannelUnsafe#beginRead()} loop may
     * drain all pending data, and then if the parent channel is reading this channel may still accept frames.
     */
    private ReadStatus readStatus = ReadStatus.IDLE;

    private Queue<Object> inboundBuffer;

    /** {@code true} after the first HEADERS frame has been written **/
    private boolean firstFrameWritten;

    // Currently the child channel and parent channel are always on the same EventLoop thread. This allows us to
    // extend the read loop of a child channel if the child channel drains its queued data during read, and the
    // parent channel is still in its read loop. The next/previous links build a doubly linked list that the parent
    // channel will iterate in its channelReadComplete to end the read cycle for each child channel in the list.
    AbstractHttp2StreamChannel next;
    AbstractHttp2StreamChannel previous;

    AbstractHttp2StreamChannel(DefaultHttp2FrameStream stream, int id,
                               boolean initialWritability, ChannelHandler inboundHandler) {
        this.stream = stream;
        writable = initialWritability;
        stream.attachment = this;
        pipeline = new DefaultChannelPipeline(this) {
            @Override
            protected void incrementPendingOutboundBytes(long size) {
                // Do thing for now
            }

            @Override
            protected void decrementPendingOutboundBytes(long size) {
                // Do thing for now
            }
        };

        closePromise = pipeline.newPromise();
        channelId = new Http2StreamChannelId(parent().id(), id);

        if (inboundHandler != null) {
            // Add the handler to the pipeline now that we are registered.
            pipeline.addLast(inboundHandler);
        }
    }

    @Override
    public Http2FrameStream stream() {
        return stream;
    }

    void closeOutbound() {
        outboundClosed = true;
    }

    void streamClosed() {
        unsafe.readEOS();
        // Attempt to drain any queued data from the queue and deliver it to the application before closing this
        // channel.
        unsafe.doBeginRead();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closePromise.isDone();
    }

    @Override
    public boolean isActive() {
        return isOpen();
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    @Override
    public ChannelId id() {
        return channelId;
    }

    @Override
    public EventLoop eventLoop() {
        return parent().eventLoop();
    }

    @Override
    public Channel parent() {
        return parentContext().channel();
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public SocketAddress localAddress() {
        return parent().localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
        return parent().remoteAddress();
    }

    @Override
    public ChannelFuture closeFuture() {
        return closePromise;
    }

    @Override
    public long bytesBeforeUnwritable() {
        // TODO: Do a proper impl
        return config().getWriteBufferHighWaterMark();
    }

    @Override
    public long bytesBeforeWritable() {
        // TODO: Do a proper impl
        return 0;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public Channel read() {
        pipeline().read();
        return this;
    }

    @Override
    public Channel flush() {
        pipeline().flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline().bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline().connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline().connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline().disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline().close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline().deregister();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline().bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline().connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline().connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline().disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline().close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline().deregister(promise);
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline().write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline().write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline().writeAndFlush(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline().writeAndFlush(msg);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline().newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline().newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline().newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline().newFailedFuture(cause);
    }

    @Override
    public ChannelPromise voidPromise() {
        return pipeline().voidPromise();
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    @Override
    public String toString() {
        return parent().toString() + "(H2 - " + stream + ')';
    }

    void writabilityChanged(boolean writable) {
        assert eventLoop().inEventLoop();
        if (writable != this.writable && isActive()) {
            // Only notify if we received a state change.
            this.writable = writable;
            pipeline().fireChannelWritabilityChanged();
        }
    }

    /**
     * Receive a read message. This does not notify handlers unless a read is in progress on the
     * channel.
     */
    void fireChildRead(Http2Frame frame) {
        assert eventLoop().inEventLoop();
        if (!isActive()) {
            ReferenceCountUtil.release(frame);
        } else if (readStatus != ReadStatus.IDLE) {
            // If a read is in progress or has been requested, there cannot be anything in the queue,
            // otherwise we would have drained it from the queue and processed it during the read cycle.
            assert inboundBuffer == null || inboundBuffer.isEmpty();
            final RecvByteBufAllocator.Handle allocHandle = unsafe.recvBufAllocHandle();
            unsafe.doRead0(frame, allocHandle);
            // We currently don't need to check for readEOS because the parent channel and child channel are limited
            // to the same EventLoop thread. There are a limited number of frame types that may come after EOS is
            // read (unknown, reset) and the trade off is less conditionals for the hot path (headers/data) at the
            // cost of additional readComplete notifications on the rare path.
            if (allocHandle.continueReading()) {
                tryAddChildChannelToReadPendingQueue();
            } else {
                tryRemoveChildChannelFromReadPendingQueue();
                unsafe.notifyReadComplete(allocHandle);
            }
        } else {
            if (inboundBuffer == null) {
                inboundBuffer = new ArrayDeque<Object>(4);
            }
            inboundBuffer.add(frame);
        }
    }

    void fireChildReadComplete() {
        assert eventLoop().inEventLoop();
        assert readStatus != ReadStatus.IDLE;
        unsafe.notifyReadComplete(unsafe.recvBufAllocHandle());
    }

    private final class Http2ChannelUnsafe implements Unsafe {
        private final VoidChannelPromise unsafeVoidPromise =
                new VoidChannelPromise(AbstractHttp2StreamChannel.this, false);
        @SuppressWarnings("deprecation")
        private RecvByteBufAllocator.Handle recvHandle;
        private boolean writeDoneAndNoFlush;
        private boolean closeInitiated;
        private boolean readEOS;

        @Override
        public void connect(final SocketAddress remoteAddress,
                            SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }
            promise.setFailure(new UnsupportedOperationException());
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
                recvHandle.reset(config());
            }
            return recvHandle;
        }

        @Override
        public SocketAddress localAddress() {
            return parent().unsafe().localAddress();
        }

        @Override
        public SocketAddress remoteAddress() {
            return parent().unsafe().remoteAddress();
        }

        @Override
        public void register(EventLoop eventLoop, ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }
            if (registered) {
                promise.setFailure(new UnsupportedOperationException("Re-register is not supported"));
                return;
            }

            registered = true;

            promise.setSuccess();

            pipeline().fireChannelRegistered();
            if (isActive()) {
                pipeline().fireChannelActive();
            }
        }

        @Override
        public void bind(SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }
            promise.setFailure(new UnsupportedOperationException());
        }

        @Override
        public void disconnect(ChannelPromise promise) {
            close(promise);
        }

        @Override
        public void close(final ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }
            if (closeInitiated) {
                if (closePromise.isDone()) {
                    // Closed already.
                    promise.setSuccess();
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closePromise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }
            closeInitiated = true;

            tryRemoveChildChannelFromReadPendingQueue();

            final boolean wasActive = isActive();

            // Only ever send a reset frame if the connection is still alive and if the stream may have existed
            // as otherwise we may send a RST on a stream in an invalid state and cause a connection error.
            if (parent().isActive() && !readEOS && streamMayHaveExisted(stream())) {
                Http2StreamFrame resetFrame = new DefaultHttp2ResetFrame(Http2Error.CANCEL).stream(stream());
                write(resetFrame, unsafe().voidPromise());
                flush();
            }

            if (inboundBuffer != null) {
                for (;;) {
                    Object msg = inboundBuffer.poll();
                    if (msg == null) {
                        break;
                    }
                    ReferenceCountUtil.release(msg);
                }
                inboundBuffer = null;
            }

            // The promise should be notified before we call fireChannelInactive().
            outboundClosed = true;
            closePromise.setSuccess();
            promise.setSuccess();

            fireChannelInactiveAndDeregister(voidPromise(), wasActive);
        }

        @Override
        public void closeForcibly() {
            close(unsafe().voidPromise());
        }

        @Override
        public void deregister(ChannelPromise promise) {
            fireChannelInactiveAndDeregister(promise, false);
        }

        private void fireChannelInactiveAndDeregister(final ChannelPromise promise,
                                                      final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                promise.setSuccess();
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is necessary to preserve the
            // behavior of the AbstractChannel, which always invokes channelUnregistered and channelInactive
            // events 'later' to ensure the current events in the handler are completed before these events.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (fireChannelInactive) {
                        pipeline.fireChannelInactive();
                    }
                    // The user can fire `deregister` events multiple times but we only want to fire the pipeline
                    // event if the channel was actually registered.
                    if (registered) {
                        registered = false;
                        pipeline.fireChannelUnregistered();
                    }
                    safeSetSuccess(promise);
                }
            });
        }

        private void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //     -> channel.unsafe.close()
                //       -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        @Override
        public void beginRead() {
            if (!isActive()) {
                return;
            }
            switch (readStatus) {
                case IDLE:
                    readStatus = ReadStatus.IN_PROGRESS;
                    doBeginRead();
                    break;
                case IN_PROGRESS:
                    readStatus = ReadStatus.REQUESTED;
                    break;
                default:
                    break;
            }
        }

        void doBeginRead() {
            Object message;
            if (inboundBuffer == null || (message = inboundBuffer.poll()) == null) {
                if (readEOS) {
                    unsafe.closeForcibly();
                }
            } else {
                final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
                allocHandle.reset(config());
                boolean continueReading = false;
                do {
                    doRead0((Http2Frame) message, allocHandle);
                } while ((readEOS || (continueReading = allocHandle.continueReading())) &&
                        (message = inboundBuffer.poll()) != null);

                if (continueReading && isParentReadInProgress() && !readEOS) {
                    // Currently the parent and child channel are on the same EventLoop thread. If the parent is
                    // currently reading it is possile that more frames will be delivered to this child channel. In
                    // the case that this child channel still wants to read we delay the channelReadComplete on this
                    // child channel until the parent is done reading.
                    boolean added = tryAddChildChannelToReadPendingQueue();
                    assert added;
                } else {
                    notifyReadComplete(allocHandle);
                }
            }
        }

        void readEOS() {
            readEOS = true;
        }

        void notifyReadComplete(RecvByteBufAllocator.Handle allocHandle) {
            assert next == null && previous == null;
            if (readStatus == ReadStatus.REQUESTED) {
                readStatus = ReadStatus.IN_PROGRESS;
            } else {
                readStatus = ReadStatus.IDLE;
            }
            allocHandle.readComplete();
            pipeline().fireChannelReadComplete();
            // Reading data may result in frames being written (e.g. WINDOW_UPDATE, RST, etc..). If the parent
            // channel is not currently reading we need to force a flush at the child channel, because we cannot
            // rely upon flush occurring in channelReadComplete on the parent channel.
            flush();
            if (readEOS) {
                unsafe.closeForcibly();
            }
        }

        @SuppressWarnings("deprecation")
        void doRead0(Http2Frame frame, RecvByteBufAllocator.Handle allocHandle) {
            pipeline().fireChannelRead(frame);
            allocHandle.incMessagesRead(1);

            if (frame instanceof Http2DataFrame) {
                final int numBytesToBeConsumed = ((Http2DataFrame) frame).initialFlowControlledBytes();
                allocHandle.attemptedBytesRead(numBytesToBeConsumed);
                allocHandle.lastBytesRead(numBytesToBeConsumed);
                if (numBytesToBeConsumed != 0) {
                    try {
                        if (consumeBytes(stream, numBytesToBeConsumed)) {
                            // We wrote some WINDOW_UPDATE frame, so we may need to do a flush.
                            writeDoneAndNoFlush = true;
                            flush();
                        }
                    } catch (Http2Exception e) {
                        pipeline().fireExceptionCaught(e);
                    }
                }
            } else {
                allocHandle.attemptedBytesRead(MIN_HTTP2_FRAME_SIZE);
                allocHandle.lastBytesRead(MIN_HTTP2_FRAME_SIZE);
            }
        }

        @Override
        public void write(Object msg, final ChannelPromise promise) {
            // After this point its not possible to cancel a write anymore.
            if (!promise.setUncancellable()) {
                ReferenceCountUtil.release(msg);
                return;
            }

            if (!isActive() ||
                    // Once the outbound side was closed we should not allow header / data frames
                    outboundClosed && (msg instanceof Http2HeadersFrame || msg instanceof Http2DataFrame)) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new ClosedChannelException());
                return;
            }

            try {
                if (msg instanceof Http2StreamFrame) {
                    Http2StreamFrame frame = validateStreamFrame((Http2StreamFrame) msg).stream(stream());
                    if (!firstFrameWritten && !isStreamIdValid(stream().id())) {
                        if (!(frame instanceof Http2HeadersFrame)) {
                            ReferenceCountUtil.release(frame);
                            promise.setFailure(
                                    new IllegalArgumentException("The first frame must be a headers frame. Was: "
                                            + frame.name()));
                            return;
                        }
                        firstFrameWritten = true;
                        ChannelFuture future = write0(parentContext(), frame);
                        if (future.isDone()) {
                            firstWriteComplete(future, promise);
                        } else {
                            future.addListener(new ChannelFutureListener() {
                                @Override
                                public void operationComplete(ChannelFuture future) {
                                    firstWriteComplete(future, promise);
                                }
                            });
                            writeDoneAndNoFlush = true;
                        }
                        return;
                    }
                } else {
                    String msgStr = msg.toString();
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(new IllegalArgumentException(
                            "Message must be an " + StringUtil.simpleClassName(Http2StreamFrame.class) +
                                    ": " + msgStr));
                    return;
                }

                ChannelFuture future = write0(parentContext(), msg);
                if (future.isDone()) {
                    writeComplete(future, promise);
                } else {
                    future.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            writeComplete(future, promise);
                        }
                    });
                    writeDoneAndNoFlush = true;
                }
            } catch (Throwable t) {
                promise.tryFailure(t);
            }
        }

        private void firstWriteComplete(ChannelFuture future, ChannelPromise promise) {
            Throwable cause = future.cause();
            if (cause == null) {
                // As we just finished our first write which made the stream-id valid we need to re-evaluate
                // the writability of the channel.
                writabilityChanged(isWritable(stream));
                promise.setSuccess();
            } else {
                // If the first write fails there is not much we can do, just close
                closeForcibly();
                promise.setFailure(wrapStreamClosedError(cause));
            }
        }

        private void writeComplete(ChannelFuture future, ChannelPromise promise) {
            Throwable cause = future.cause();
            if (cause == null) {
                promise.setSuccess();
            } else {
                Throwable error = wrapStreamClosedError(cause);
                // To make it more consistent with AbstractChannel we handle all IOExceptions here.
                if (error instanceof IOException) {
                    if (config.isAutoClose()) {
                        // Close channel if needed.
                        closeForcibly();
                    } else {
                        // TODO: Once Http2StreamChannel extends DuplexChannel we should call shutdownOutput(...)
                        outboundClosed = true;
                    }
                }
                promise.setFailure(error);
            }
        }

        private Throwable wrapStreamClosedError(Throwable cause) {
            // If the error was caused by STREAM_CLOSED we should use a ClosedChannelException to better
            // mimic other transports and make it easier to reason about what exceptions to expect.
            if (cause instanceof Http2Exception && ((Http2Exception) cause).error() == Http2Error.STREAM_CLOSED) {
                return new ClosedChannelException().initCause(cause);
            }
            return cause;
        }

        private Http2StreamFrame validateStreamFrame(Http2StreamFrame frame) {
            if (frame.stream() != null && frame.stream() != stream) {
                String msgString = frame.toString();
                ReferenceCountUtil.release(frame);
                throw new IllegalArgumentException(
                        "Stream " + frame.stream() + " must not be set on the frame: " + msgString);
            }
            return frame;
        }

        @Override
        public void flush() {
            // If we are currently in the parent channel's read loop we should just ignore the flush.
            // We will ensure we trigger ctx.flush() after we processed all Channels later on and
            // so aggregate the flushes. This is done as ctx.flush() is expensive when as it may trigger an
            // write(...) or writev(...) operation on the socket.
            if (!writeDoneAndNoFlush || isParentReadInProgress()) {
                // There is nothing to flush so this is a NOOP.
                return;
            }
            try {
                flush0(parentContext());
            } finally {
                writeDoneAndNoFlush = false;
            }
        }

        @Override
        public ChannelPromise voidPromise() {
            return unsafeVoidPromise;
        }

        @Override
        public ChannelOutboundBuffer outboundBuffer() {
            // Always return null as we not use the ChannelOutboundBuffer and not even support it.
            return null;
        }
    }

    /**
     * {@link ChannelConfig} so that the high and low writebuffer watermarks can reflect the outbound flow control
     * window, without having to create a new {@link WriteBufferWaterMark} object whenever the flow control window
     * changes.
     */
    private final class Http2StreamChannelConfig extends DefaultChannelConfig {
        Http2StreamChannelConfig(Channel channel) {
            super(channel);
        }

        @Override
        public int getWriteBufferHighWaterMark() {
            return min(parent().config().getWriteBufferHighWaterMark(), initialOutboundStreamWindow());
        }

        @Override
        public int getWriteBufferLowWaterMark() {
            return min(parent().config().getWriteBufferLowWaterMark(), initialOutboundStreamWindow());
        }

        @Override
        public MessageSizeEstimator getMessageSizeEstimator() {
            return FlowControlledFrameSizeEstimator.INSTANCE;
        }

        @Override
        public WriteBufferWaterMark getWriteBufferWaterMark() {
            int mark = getWriteBufferHighWaterMark();
            return new WriteBufferWaterMark(mark, mark);
        }

        @Override
        public ChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
            throw new UnsupportedOperationException();
        }

        @Override
        @Deprecated
        public ChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
            throw new UnsupportedOperationException();
        }

        @Override
        @Deprecated
        public ChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) {
            if (!(allocator.newHandle() instanceof RecvByteBufAllocator.ExtendedHandle)) {
                throw new IllegalArgumentException("allocator.newHandle() must return an object of type: " +
                        RecvByteBufAllocator.ExtendedHandle.class);
            }
            super.setRecvByteBufAllocator(allocator);
            return this;
        }
    }

    protected void flush0(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    protected ChannelFuture write0(ChannelHandlerContext ctx, Object msg) {
        ChannelPromise promise = ctx.newPromise();
        ctx.write(msg, promise);
        return promise;
    }

    protected abstract int initialOutboundStreamWindow();
    protected abstract boolean isWritable(DefaultHttp2FrameStream stream);
    protected abstract boolean consumeBytes(Http2FrameStream stream, int bytes) throws Http2Exception;
    protected abstract boolean isParentReadInProgress();
    protected abstract boolean streamMayHaveExisted(Http2FrameStream stream);
    protected abstract void tryRemoveChildChannelFromReadPendingQueue();
    protected abstract boolean tryAddChildChannelToReadPendingQueue();
    protected abstract ChannelHandlerContext parentContext();
}
