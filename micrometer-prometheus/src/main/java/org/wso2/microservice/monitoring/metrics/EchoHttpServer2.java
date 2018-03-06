/*
 *
 *   Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 * /
 */
package org.wso2.microservice.monitoring.metrics;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

/**
 * TODO: Class level comment.
 */
public class EchoHttpServer2 {
    private static final Logger logger = LoggerFactory.getLogger(EchoHttpServer2.class);
    private static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Parameter(names = "--port", description = "Server Port")
    private int port = 8682;

    @Parameter(names = "--boss-threads", description = "Boss Threads")
    private int bossThreads = Runtime.getRuntime().availableProcessors();

    @Parameter(names = "--worker-threads", description = "Worker Threads")
    private int workerThreads = 200;

    @Parameter(names = "--enable-ssl", description = "Enable SSL")
    private boolean enableSSL = false;

    @Parameter(names = "--sleep-time", description = "Sleep Time in milliseconds")
    private int sleepTime = 0;

    @Parameter(names = {"-h", "--help"}, description = "Display Help", help = true)
    private boolean help = false;

    public static void main(String[] args) throws Exception {
        EchoHttpServer2 echoHttpServer = new EchoHttpServer2();
        final JCommander jcmdr = new JCommander(echoHttpServer);
        jcmdr.setProgramName(EchoHttpServer2.class.getSimpleName());
        jcmdr.parse(args);

        if (echoHttpServer.help) {
            jcmdr.usage();
            return;
        }

        echoHttpServer.startServer();
    }

    private void startServer() throws IOException, CertificateException, InterruptedException {
        long startTime = System.nanoTime();

        logger.info("Echo HTTP Server. Port: {}, Boss Threads: {}, Worker Threads: {}, SSL Enabled: {}" +
                ", Sleep Time: {}ms", new Object[]{port, bossThreads, workerThreads, enableSSL, sleepTime});
        // Print Max Heap Size
        logger.info("Max Heap Size: {}MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        // Print Netty Version
        Version version = Version.identify(this.getClass().getClassLoader()).values().iterator().next();
        logger.info("Netty Version: {}", version.artifactVersion());
        // Configure SSL.
        final SslContext sslCtx;
        if (enableSSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        EventLoopGroup bossGroup = new NioEventLoopGroup(bossThreads);
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }
                            p.addLast(new HttpServerCodec());
                            p.addLast("aggregator", new HttpObjectAggregator(1048576));
                            p.addLast(new EchoHttpServerHandler2(sleepTime));
                        }
                    });

            Counter counter = registry.counter("micro_counter");
            counter.increment();

            Timer timer = registry.timer("timer");

            timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

            // Start the server.
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(port).sync();

            //expose prometheus metrics
            HttpServer server = HttpServer.create(new InetSocketAddress(8082), 0);
            server.createContext("/metrics", httpExchange -> {
                String response = registry.scrape();
                httpExchange.sendResponseHeaders(200, response.length());
                OutputStream os = httpExchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            });

            server.start();

            // Wait until the server socket is closed.
            // In this example, this does not happen, but you can do that to gracefully
            // shut down your server.

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
