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
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
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
import io.prometheus.client.dropwizard.DropwizardExports;
import io.prometheus.client.exporter.common.TextFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * TODO: Class level comment.
 */
public class EchoHttpServer1 {
    private static final Logger logger = LoggerFactory.getLogger(EchoHttpServer1.class);
    private static final MetricRegistry dropwizardRegistry = new MetricRegistry();
    private static final CollectorRegistry prometheusRegistry = new CollectorRegistry();

    @Parameter(names = "--port", description = "Server Port")
    private int port = 8681;

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
        EchoHttpServer1 echoHttpServer = new EchoHttpServer1();
        final JCommander jcmdr = new JCommander(echoHttpServer);
        jcmdr.setProgramName(EchoHttpServer1.class.getSimpleName());
        jcmdr.parse(args);

        if (echoHttpServer.help) {
            jcmdr.usage();
            return;
        }
        new DropwizardExports(dropwizardRegistry).register(prometheusRegistry);

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
                            p.addLast(new EchoHttpServerHandler1(sleepTime));
                        }
                    });

            Meter requests = dropwizardRegistry.meter("meter");
            requests.mark();
            Counter counter = dropwizardRegistry.counter("counter");
            counter.inc();
            Histogram histogram = dropwizardRegistry.histogram("histogram");
            histogram.update(24);
            Timer timer = dropwizardRegistry.timer("timer");
            counter.inc(10);
            TimeUnit.NANOSECONDS.sleep(100);
            timer.update(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

            Queue queue = new LinkedList();
            queue.add("1");
            queue.add("2");
            queue.add("3");
            dropwizardRegistry.register("queue", (Gauge<Integer>) () -> queue.size());
            queue.add("4");

            // Start the server.
            // Bind and start to accept incoming connections.
            ChannelFuture f = b.bind(port).sync();


            // Expose dropwizard metrics through console reporter
            ConsoleReporter reporter = ConsoleReporter.forRegistry(dropwizardRegistry)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .build();
            reporter.start(0, 2, TimeUnit.MINUTES);

            //Expose prometheus metrics
            HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
            server.createContext("/", httpExchange -> {
                if ("/metrics".equals(httpExchange.getRequestURI().getPath())) {
                    StringWriter respBodyWriter = new StringWriter();
                    TextFormat.write004(respBodyWriter, prometheusRegistry.metricFamilySamples());
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
