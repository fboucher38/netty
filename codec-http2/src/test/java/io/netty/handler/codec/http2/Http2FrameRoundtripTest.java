/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests encoding/decoding each HTTP2 frame type.
 */
public class Http2FrameRoundtripTest {

    @Mock
    private Http2FrameObserver serverObserver;

    private ArgumentCaptor<ByteBuf> dataCaptor;
    private Http2FrameWriter frameWriter;
    private ServerBootstrap sb;
    private Bootstrap cb;
    private Channel serverChannel;
    private Channel clientChannel;
    private CountDownLatch requestLatch;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        requestLatch = new CountDownLatch(1);
        frameWriter = new DefaultHttp2FrameWriter();
        dataCaptor = ArgumentCaptor.forClass(ByteBuf.class);

        sb = new ServerBootstrap();
        cb = new Bootstrap();

        sb.group(new NioEventLoopGroup(), new NioEventLoopGroup());
        sb.channel(NioServerSocketChannel.class);
        sb.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast("reader", new FrameAdapter(serverObserver));
            }
        });

        cb.group(new NioEventLoopGroup());
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast("reader", new FrameAdapter(null));
            }
        });

        serverChannel = sb.bind(new InetSocketAddress(0)).sync().channel();
        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        ChannelFuture ccf = cb.connect(new InetSocketAddress(NetUtil.LOCALHOST, port));
        assertTrue(ccf.awaitUninterruptibly().isSuccess());
        clientChannel = ccf.channel();
    }

    @After
    public void teardown() throws Exception {
        serverChannel.close().sync();
        sb.group().shutdownGracefully();
        cb.group().shutdownGracefully();
    }

    @Test
    public void dataFrameShouldMatch() throws Exception {
        String text = "hello world";

        frameWriter.writeData(ctx(), newPromise(), 0x7FFFFFFF,
                Unpooled.copiedBuffer(text.getBytes()), 100, true, false, false);

        awaitRequests();
        verify(serverObserver).onDataRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                dataCaptor.capture(), eq(100), eq(true), eq(false), eq(false));
    }

    @Test
    public void headersFrameWithoutPriorityShouldMatch() throws Exception {
        Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        frameWriter.writeHeaders(ctx(), newPromise(), 0x7FFFFFFF, headers, 0, true, false);

        awaitRequests();
        verify(serverObserver).onHeadersRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(headers), eq(0), eq(true), eq(false));
    }

    @Test
    public void headersFrameWithPriorityShouldMatch() throws Exception {
        Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        frameWriter.writeHeaders(ctx(), newPromise(), 0x7FFFFFFF, headers, 4, (short) 255, true, 0,
                true, false);

        awaitRequests();
        verify(serverObserver).onHeadersRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(headers), eq(4), eq((short) 255), eq(true), eq(0), eq(true), eq(false));
    }

    @Test
    public void goAwayFrameShouldMatch() throws Exception {
        String text = "test";
        frameWriter.writeGoAway(ctx(), newPromise(), 0x7FFFFFFF, 0xFFFFFFFFL,
                Unpooled.copiedBuffer(text.getBytes()));

        awaitRequests();
        verify(serverObserver).onGoAwayRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(0xFFFFFFFFL), dataCaptor.capture());
    }

    @Test
    public void pingFrameShouldMatch() throws Exception {
        ByteBuf buf = Unpooled.copiedBuffer("01234567", UTF_8);
        frameWriter.writePing(ctx(), ctx().newPromise(), true, buf);

        awaitRequests();
        verify(serverObserver).onPingAckRead(any(ChannelHandlerContext.class), dataCaptor.capture());
    }

    @Test
    public void priorityFrameShouldMatch() throws Exception {
        frameWriter.writePriority(ctx(), newPromise(), 0x7FFFFFFF, 1, (short) 1, true);

        awaitRequests();
        verify(serverObserver).onPriorityRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(1), eq((short) 1), eq(true));
    }

    @Test
    public void pushPromiseFrameShouldMatch() throws Exception {
        Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        frameWriter.writePushPromise(ctx(), newPromise(), 0x7FFFFFFF, 1, headers, 5);

        awaitRequests();
        verify(serverObserver).onPushPromiseRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(1), eq(headers), eq(5));
    }

    @Test
    public void rstStreamFrameShouldMatch() throws Exception {
        frameWriter.writeRstStream(ctx(), newPromise(), 0x7FFFFFFF, 0xFFFFFFFFL);

        awaitRequests();
        verify(serverObserver).onRstStreamRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(0xFFFFFFFFL));
    }

    @Test
    public void settingsFrameShouldMatch() throws Exception {
        Http2Settings settings = new Http2Settings();
        settings.allowCompressedData(true);
        settings.initialWindowSize(10);
        settings.maxConcurrentStreams(1000);
        settings.maxHeaderTableSize(4096);
        frameWriter.writeSettings(ctx(), newPromise(), settings);

        awaitRequests();
        verify(serverObserver).onSettingsRead(any(ChannelHandlerContext.class), eq(settings));
    }

    @Test
    public void windowUpdateFrameShouldMatch() throws Exception {
        frameWriter.writeWindowUpdate(ctx(), newPromise(), 0x7FFFFFFF, 0x7FFFFFFF);

        awaitRequests();
        verify(serverObserver).onWindowUpdateRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(0x7FFFFFFF));
    }

    @Test
    public void stressTest() throws Exception {
        Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        String text = "hello world";
        int numStreams = 1000;
        int expectedFrames = numStreams * 2;
        requestLatch = new CountDownLatch(expectedFrames);

        for (int i = 1; i < numStreams + 1; ++i) {
            frameWriter.writeHeaders(ctx(), newPromise(), i, headers, 0, (short) 16, false, 0,
                    false, false);
            frameWriter.writeData(ctx(), newPromise(), i, Unpooled.copiedBuffer(text.getBytes()),
                    0, true, true, false);
        }

        // Wait for all frames to be received.
        awaitRequests();
    }

    private void awaitRequests() throws InterruptedException {
        requestLatch.await(5, SECONDS);
    }

    private ChannelHandlerContext ctx() {
        return clientChannel.pipeline().firstContext();
    }

    private ChannelPromise newPromise() {
        return ctx().newPromise();
    }

    private final class FrameAdapter extends ByteToMessageDecoder {

        private final Http2FrameObserver observer;
        private final DefaultHttp2FrameReader reader;

        FrameAdapter(Http2FrameObserver observer) {
            this.observer = observer;
            reader = new DefaultHttp2FrameReader();
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
                throws Exception {
            reader.readFrame(ctx, in, new Http2FrameObserver() {

                @Override
                public void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data,
                        int padding, boolean endOfStream, boolean endOfSegment, boolean compressed)
                        throws Http2Exception {
                    observer.onDataRead(ctx, streamId, copy(data), padding, endOfStream,
                            endOfSegment, compressed);
                    requestLatch.countDown();
                }

                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                        Http2Headers headers, int padding, boolean endStream, boolean endSegment)
                        throws Http2Exception {
                    observer.onHeadersRead(ctx, streamId, headers, padding, endStream, endSegment);
                    requestLatch.countDown();
                }

                @Override
                public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                        Http2Headers headers, int streamDependency, short weight,
                        boolean exclusive, int padding, boolean endStream, boolean endSegment)
                        throws Http2Exception {
                    observer.onHeadersRead(ctx, streamId, headers, streamDependency, weight,
                            exclusive, padding, endStream, endSegment);
                    requestLatch.countDown();
                }

                @Override
                public void onPriorityRead(ChannelHandlerContext ctx, int streamId,
                        int streamDependency, short weight, boolean exclusive)
                        throws Http2Exception {
                    observer.onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
                    requestLatch.countDown();
                }

                @Override
                public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
                        throws Http2Exception {
                    observer.onRstStreamRead(ctx, streamId, errorCode);
                    requestLatch.countDown();
                }

                @Override
                public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {
                    observer.onSettingsAckRead(ctx);
                    requestLatch.countDown();
                }

                @Override
                public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings)
                        throws Http2Exception {
                    observer.onSettingsRead(ctx, settings);
                    requestLatch.countDown();
                }

                @Override
                public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
                    observer.onPingRead(ctx, copy(data));
                    requestLatch.countDown();
                }

                @Override
                public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception {
                    observer.onPingAckRead(ctx, copy(data));
                    requestLatch.countDown();
                }

                @Override
                public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                        Http2Headers headers, int padding) throws Http2Exception {
                    observer.onPushPromiseRead(ctx, streamId, promisedStreamId, headers, padding);
                    requestLatch.countDown();
                }

                @Override
                public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId,
                        long errorCode, ByteBuf debugData) throws Http2Exception {
                    observer.onGoAwayRead(ctx, lastStreamId, errorCode, copy(debugData));
                    requestLatch.countDown();
                }

                @Override
                public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId,
                        int windowSizeIncrement) throws Http2Exception {
                    observer.onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
                    requestLatch.countDown();
                }

                @Override
                public void onAltSvcRead(ChannelHandlerContext ctx, int streamId, long maxAge,
                        int port, ByteBuf protocolId, String host, String origin)
                        throws Http2Exception {
                    observer.onAltSvcRead(ctx, streamId, maxAge, port, copy(protocolId), host,
                            origin);
                    requestLatch.countDown();
                }

                @Override
                public void onBlockedRead(ChannelHandlerContext ctx, int streamId) throws Http2Exception {
                    observer.onBlockedRead(ctx, streamId);
                    requestLatch.countDown();
                }
            });
        }

        ByteBuf copy(ByteBuf buffer) {
            return Unpooled.copiedBuffer(buffer);
        }
    }
}
