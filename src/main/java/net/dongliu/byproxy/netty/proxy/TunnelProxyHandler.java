package net.dongliu.byproxy.netty.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Promise;
import net.dongliu.byproxy.MessageListener;
import net.dongliu.byproxy.netty.ClientSSLContextFactory;
import net.dongliu.byproxy.netty.NettySettings;
import net.dongliu.byproxy.netty.SSLContextManager;
import net.dongliu.byproxy.netty.detector.AnyMatcher;
import net.dongliu.byproxy.netty.detector.ProtocolDetector;
import net.dongliu.byproxy.netty.detector.SSLMatcher;
import net.dongliu.byproxy.netty.interceptor.HttpInterceptor;
import net.dongliu.byproxy.utils.NetAddress;

import java.util.function.Supplier;

/**
 * for socks/http connect tunnel
 */
public abstract class TunnelProxyHandler<T> extends SimpleChannelInboundHandler<T> {
    private final MessageListener messageListener;
    private final SSLContextManager sslContextManager;
    private final Supplier<ProxyHandler> proxyHandlerSupplier;

    public TunnelProxyHandler(MessageListener messageListener, SSLContextManager sslContextManager,
                              Supplier<ProxyHandler> proxyHandlerSupplier) {
        this.messageListener = messageListener;
        this.sslContextManager = sslContextManager;
        this.proxyHandlerSupplier = proxyHandlerSupplier;
    }

    protected Bootstrap initBootStrap(Promise<Channel> promise, EventLoopGroup eventLoopGroup) {
        return new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, NettySettings.CONNECT_TIMEOUT)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ProxyHandler proxyHandler = proxyHandlerSupplier.get();
                        if (proxyHandler != null) {
                            ch.pipeline().addLast(proxyHandler);
                        }
                        ch.pipeline().addLast(new ChannelActiveAwareHandler(promise));
                    }
                });
    }

    protected void initTcpProxyHandlers(ChannelHandlerContext ctx, NetAddress address, Channel outChannel) {
        boolean intercept = messageListener != null;
        if (!intercept) {
            ctx.pipeline().addLast(new ReplayHandler(outChannel));
            outChannel.pipeline().addLast(new ReplayHandler(ctx.channel()));
            return;
        }

        ProtocolDetector protocolDetector = new ProtocolDetector(
                new SSLMatcher(pipeline -> {
                    SslContext serverContext = sslContextManager.createSSlContext(address.getHost());
                    pipeline.addLast("ssl", serverContext.newHandler(ctx.alloc()));
//                    pipeline.addLast(new ProtocolNegotiationHandler());

                    SslContextBuilder.forClient().trustManager();
                    SslContext sslContext = ClientSSLContextFactory.getInstance().get();
                    SslHandler sslHandler = sslContext.newHandler(ctx.alloc(), address.getHost(), address.getPort());
                    outChannel.pipeline().addLast(sslHandler);
                    initInterceptorHandler(ctx, address, messageListener, outChannel, true);
                }),
                new AnyMatcher(p -> initInterceptorHandler(ctx, address, messageListener, outChannel, false))
        );
        ctx.pipeline().addLast(protocolDetector);
    }

    private static void initInterceptorHandler(ChannelHandlerContext ctx, NetAddress address,
                                               MessageListener messageListener,
                                               Channel outboundChannel, boolean ssl) {

        ctx.pipeline().addLast(new HttpServerCodec());
        ctx.pipeline().addLast("", new HttpServerExpectContinueHandler());
        ctx.pipeline().addLast("tcp-tunnel-handler", new ReplayHandler(outboundChannel));

        outboundChannel.pipeline().addLast(new HttpClientCodec());
        HttpInterceptor interceptor = new HttpInterceptor(ssl, address, messageListener, () -> {
            ctx.pipeline().remove(HttpServerCodec.class);
            WebSocketFrameDecoder frameDecoder = new WebSocket13FrameDecoder(true, true, 65536, false);
            WebSocketFrameEncoder frameEncoder = new WebSocket13FrameEncoder(false);
            ctx.pipeline().addBefore("tcp-tunnel-handler", "ws-decoder", frameDecoder);
            ctx.pipeline().addBefore("tcp-tunnel-handler", "ws-encoder", frameEncoder);
        });
        outboundChannel.pipeline().addLast("http-interceptor", interceptor);
        outboundChannel.pipeline().addLast("tcp-tunnel-handler", new ReplayHandler(ctx.channel()));

    }
}