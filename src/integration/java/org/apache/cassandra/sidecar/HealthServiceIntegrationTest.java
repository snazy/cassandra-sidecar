/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.sidecar;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.datastax.driver.core.NettyOptions;
import com.datastax.oss.simulacron.common.cluster.ClusterSpec;
import com.datastax.oss.simulacron.server.BoundCluster;
import com.datastax.oss.simulacron.server.BoundNode;
import com.datastax.oss.simulacron.server.NodePerPortResolver;
import com.datastax.oss.simulacron.server.Server;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxTestContext;

import org.apache.cassandra.sidecar.routes.HealthCheck;
import org.apache.cassandra.sidecar.routes.HealthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Longer run and more intensive tests for the HealthService and HealthCheck
 */
@DisplayName("Health Service Integration Tests")
public class HealthServiceIntegrationTest
{
    private static final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                                                       .setDaemon(true)
                                                       .setNameFormat("HealthServiceTest-%d")
                                                       .build();
    private static final HashedWheelTimer sharedHWT = new HashedWheelTimer(threadFactory);
    private static final EventLoopGroup sharedEventLoopGroup = new NioEventLoopGroup(0, threadFactory);

    private static final Logger logger = LoggerFactory.getLogger(HealthServiceIntegrationTest.class);

    private static final NettyOptions shared = new NettyOptions()
    {
        public EventLoopGroup eventLoopGroup(ThreadFactory threadFactory)
        {
            return sharedEventLoopGroup;
        }

        public void onClusterClose(EventLoopGroup eventLoopGroup)
        {
        }

        public Timer timer(ThreadFactory threadFactory)
        {
            return sharedHWT;
        }

        public void onClusterClose(Timer timer)
        {
        }
    };

    private Vertx vertx;
    private Router router;
    private HttpServer httpServer;
    private int port;
    private List<CQLSession> sessions = new LinkedList<>();

    @BeforeEach
    void setUp() throws IOException
    {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
        ServerSocket socket = new ServerSocket(0);
        port = socket.getLocalPort();
        httpServer = vertx.createHttpServer(new HttpServerOptions()
                                            .setPort(port)
                                            .setLogActivity(true));
    }

    @AfterEach
    void tearDown()
    {
        vertx.close();
    }

    @AfterEach
    public void closeClusters()
    {
        for (CQLSession session : sessions)
            session.close();
        sessions.clear();
    }

    /**
     * This test has a race condition that can result in test failure.  Be sure to wait long enough for the server
     * to register as up.
     * See CASSANDRA-15615
     */
    @DisplayName("100 node cluster stopping, then starting")
    @Test
    public void testDownHost() throws InterruptedException
    {
        int nodeCount = 100;
        try (Server server = Server.builder()
                                   .withMultipleNodesPerIp(true)
                                   .withAddressResolver(new NodePerPortResolver(new byte[]{ 127, 0, 0, 1 }, 49152))
                                   .build())
        {
            ClusterSpec cluster = ClusterSpec.builder()
                                             .withNodes(nodeCount)
                                             .build();
            BoundCluster bCluster = server.register(cluster);

            Set<BoundNode> downNodes = new HashSet<>();
            Map<BoundNode, HealthCheck> checks = new HashMap<>();

            logger.info("Create a health check per node");
            for (BoundNode node : bCluster.getNodes())
                checks.put(node, healthCheckFor(node, shared));

            logger.info("verify all nodes marked as up");
            for (BoundNode node : bCluster.getNodes())
                assertTrue(checks.get(node).get());

            logger.info("shut down nodes one at a time, and verify we get correct response on all HealthChecks");
            for (int i = 0; downNodes.size() < nodeCount; i++)
            {
                for (BoundNode node : bCluster.getNodes())
                    assertEquals(checks.get(node).get(), !downNodes.contains(node));
                bCluster.node(i).stop();
                downNodes.add(bCluster.node(i));
            }

            logger.info("all hosts should be down");
            for (BoundNode node : bCluster.getNodes())
                assertFalse(checks.get(node).get());

            logger.info("Starting nodes back up");

            int i;
            for (i = 0; downNodes.size() > 0; i++)
            {
                bCluster.node(i).start();
                downNodes.remove(bCluster.node(i));
            }
            logger.info("Nodes started back up: " + i);

            logger.info("verify all nodes marked as up");

            long start = System.currentTimeMillis();

            int checkNumber = 0;
            for (BoundNode node : bCluster.getNodes())
            {
                while ((System.currentTimeMillis() - start) < 20000 && !checks.get(node).get())
                    Thread.sleep(250);

                logger.info("Started node " + checkNumber);
                assertTrue(checks.get(node).get(), "Failed on node " + checkNumber);
                checkNumber++;
            }
        }
    }


    @DisplayName("Down on startup, then comes up")
    @Test
    public void testDownHostTurnsOn() throws Throwable
    {
        VertxTestContext testContext = new VertxTestContext();
        try (Server server = Server.builder()
                                   .withMultipleNodesPerIp(true)
                                   .withAddressResolver(new NodePerPortResolver(new byte[]{ 127, 0, 0, 1 }, 49152))
                                   .build())
        {
            ClusterSpec cluster = ClusterSpec.builder()
                                             .withNodes(1)
                                             .build();
            BoundCluster bCluster = server.register(cluster);

            BoundNode node = bCluster.node(0);
            node.stop();
            CQLSession session = new CQLSession(node.inetSocketAddress(), shared);
            sessions.add(session);
            HealthCheck check = new HealthCheck(session);
            HealthService service = new HealthService(new Configuration.Builder()
                                                      .setHealthCheckFrequency(1000)
                                                      .build(),
                                                      check, session);
            service.start();
            try
            {
                router.route("/health").handler(service::handleHealth);
                httpServer.requestHandler(router);
                httpServer.listen();

                WebClient client = WebClient.create(vertx);
                long start = System.currentTimeMillis();
                client.get(port, "localhost", "/health")
                      .as(BodyCodec.string())
                      .send(testContext.succeeding(response -> testContext.verify(() ->
                      {
                          assertEquals(503, response.statusCode());

                          node.start();
                          while ((System.currentTimeMillis() - start) < (1000 * 60 * 2) && !check.get())
                              Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
                          service.refreshNow();
                          client.get(port, "localhost", "/health")
                                .as(BodyCodec.string())
                                .send(testContext.succeeding(upResponse -> testContext.verify(() ->
                                {
                                    assertEquals(200, upResponse.statusCode());
                                    testContext.completeNow();
                                })));
                      })));
                assertTrue(testContext.awaitCompletion(125, TimeUnit.SECONDS));
                if (testContext.failed())
                {
                    throw testContext.causeOfFailure();
                }
            }
            finally
            {
                service.stop();
            }
        }
    }

    public HealthCheck healthCheckFor(BoundNode node, NettyOptions shared)
    {
        CQLSession session = new CQLSession(node.inetSocketAddress(), shared);
        sessions.add(session);
        return new HealthCheck(session);
    }
}
