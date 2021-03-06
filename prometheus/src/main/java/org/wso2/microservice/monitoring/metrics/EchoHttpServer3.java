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
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.Summary;
import io.prometheus.client.exporter.common.TextFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Collections;
import javax.net.ssl.SSLException;

/**
 * TODO: Class level comment.
 */
public class EchoHttpServer3 {
    private static final Logger logger = LoggerFactory.getLogger(EchoHttpServer3.class);
    private static final CollectorRegistry registry = new CollectorRegistry();

    @Parameter(names = "--port", description = "Server Port")
    private int port = 8683;

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
        EchoHttpServer3 echoHttpServer = new EchoHttpServer3();
        final JCommander jcmdr = new JCommander(echoHttpServer);
        jcmdr.setProgramName(EchoHttpServer3.class.getSimpleName());
        jcmdr.parse(args);

        if (echoHttpServer.help) {
            jcmdr.usage();
            return;
        }

        echoHttpServer.startServer();
    }

    private void startServer() throws IOException, CertificateException, InterruptedException {
        logger.info("Echo HTTP Server. Port: {}, Boss Threads: {}, Worker Threads: {}, SSL Enabled: {}" +
                ", Sleep Time: {}ms", new Object[]{port, bossThreads, workerThreads, enableSSL, sleepTime});
        // Print Max Heap Size
        logger.info("Max Heap Size: {}MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        // Print Netty Version
        Version version = Version.identify(this.getClass().getClassLoader()).values().iterator().next();
        logger.info("Netty Version: {}", version.artifactVersion());

        Counter counter = Counter.build()
                .name("requests_total").help("Requests total").register(registry);
        Gauge gauge = Gauge.build()
                .name("inprogress_requests").help("Inprogress Requests").register(registry);
        gauge.setToCurrentTime();
        Histogram requestLatency = Histogram.build()
                .name("requests_latency_seconds").help("Request latency in seconds.").register(registry);
        Histogram.Timer requestTimer = requestLatency.startTimer();
        Summary sTimmer = Summary.build()
                .name("requests_latency").help("Request latency").register(registry);
        Summary.Timer summeryTimer = sTimmer.startTimer();

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
                            p.addLast(new EchoHttpServerHandler3(sleepTime));
                        }
                    });

            counter.inc(2);
            gauge.dec();

            requestTimer.observeDuration();
            summeryTimer.observeDuration();

            // Start the server.
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(port).sync();

            HttpServer server = HttpServer.create(new InetSocketAddress(8083), 0);
            server.createContext("/", httpExchange -> {
                if ("/metrics".equals(httpExchange.getRequestURI().getPath())) {
                    StringWriter respBodyWriter = new StringWriter();
                    TextFormat.write004(respBodyWriter, registry.metricFamilySamples());
                    byte[] respBody = respBodyWriter.toString().getBytes("UTF-8");
                    httpExchange.getResponseHeaders().put("Context-Type", Collections.singletonList("text/plain; charset=UTF-8"));
                    httpExchange.sendResponseHeaders(200, respBody.length);
                    httpExchange.getResponseBody().write(respBody);
                    httpExchange.getResponseBody().close();
                }
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
