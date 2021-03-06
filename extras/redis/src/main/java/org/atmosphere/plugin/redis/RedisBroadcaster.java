/*
 * Copyright 2011 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.plugin.redis;


import org.apache.commons.pool.impl.GenericObjectPool;
import org.atmosphere.util.AbstractBroadcasterProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisException;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.net.URI;

/**
 * Simple {@link org.atmosphere.cpr.Broadcaster} implementation based on Jedis
 *
 * @author Jeanfrancois Arcand
 */
public class RedisBroadcaster extends AbstractBroadcasterProxy {

    private static final Logger logger = LoggerFactory.getLogger(RedisBroadcaster.class);

    private static final String REDIS_AUTH = RedisBroadcaster.class.getName() + ".authorization";
    private static final String REDIS_SERVER = RedisBroadcaster.class.getName() + ".server";

    private Jedis jedisSubscriber;
    private Jedis jedisPublisher;
    private JedisPool jedisPool;
    private URI uri;
    private String authToken = "atmosphere";

    public RedisBroadcaster() {
        this(RedisBroadcaster.class.getSimpleName(), URI.create("http://localhost:6379"));
    }

    public RedisBroadcaster(String id) {
        this(id, URI.create("http://localhost:6379"));
    }

    public RedisBroadcaster(URI uri) {
        this(RedisBroadcaster.class.getSimpleName(), uri);
    }

    public RedisBroadcaster(String id, URI uri) {
        super(id);
        this.uri = uri;
    }

    public String getAuth() {
        return authToken;
    }

    public void setAuth(String auth) {
        authToken = auth;
    }

    @Override
    protected void start() {
        super.start();
    }

    public synchronized void setUp() {
        if (uri == null) return;

        if (config != null) {
            if (config.getServletConfig().getInitParameter(REDIS_AUTH) != null) {
                authToken = config.getServletConfig().getInitParameter(REDIS_AUTH);
            }

            if (config.getServletConfig().getInitParameter(REDIS_SERVER) != null) {
                uri = URI.create(config.getServletConfig().getInitParameter(REDIS_SERVER));
            }
        }

        // setup is synchronized, no need to sync here as well.
        if (jedisPool == null) {
            GenericObjectPool.Config config = new GenericObjectPool.Config();
            config.testOnBorrow = true;
            config.testWhileIdle = true;
            jedisPool = new JedisPool(config, uri.getHost(), uri.getPort());
        } else {
            jedisPool.returnResource(jedisPublisher);
            jedisPool.returnResource(jedisSubscriber);
        }

        jedisSubscriber = jedisPool.getResource();

        try {
            jedisSubscriber.connect();
        } catch (IOException e) {
            logger.error("failed to connect subscriber", e);
        }

        jedisSubscriber.auth(authToken);
        jedisSubscriber.flushAll();

        jedisPublisher = jedisPool.getResource();
        try {
            jedisPublisher.connect();
        } catch (IOException e) {
            logger.error("failed to connect publisher", e);
        }
        jedisPublisher.auth(authToken);
        jedisPublisher.flushAll();
    }

    @Override
    public synchronized void setID(String id) {
        super.setID(id);
        setUp();

        reconfigure();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void destroy() {
        super.destroy();
        try {
            disconnectPublisher();
            disconnectSubscriber();
            jedisPool.destroy();
        } catch (Throwable t) {
            logger.warn("Jedis error on close", t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incomingBroadcast() {
        logger.info("Subscribing to: {}", getID());

        jedisSubscriber.subscribe(new JedisPubSub() {

            public void onMessage(String channel, String message) {
                broadcastReceivedMessage(message);
            }

            public void onSubscribe(String channel, int subscribedChannels) {
                logger.debug("onSubscribe: {}", channel);
            }

            public void onUnsubscribe(String channel, int subscribedChannels) {
                logger.debug("onUnsubscribe: {}", channel);
            }

            public void onPSubscribe(String pattern, int subscribedChannels) {
                logger.debug("onPSubscribe: {}", pattern);
            }

            public void onPUnsubscribe(String pattern, int subscribedChannels) {
                logger.debug("onPUnsubscribe: {}", pattern);
            }

            public void onPMessage(String pattern, String channel, String message) {
                logger.debug("onPMessage: {}", pattern + " " + channel + " " + message);
            }
        }, getID());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outgoingBroadcast(Object message) {
        try {
            jedisPublisher.publish(getID(), message.toString());
        } catch (JedisException e) {
            logger.warn("outgoingBroadcast exception, retying a connection", e);
            synchronized (jedisPublisher) {
                jedisPool.returnBrokenResource(jedisPublisher);
                jedisPublisher = jedisPool.getResource();
                // Try a second time.
                jedisPublisher.publish(getID(), message.toString());
            }
        }
    }

    private void disconnectSubscriber() {
        if (jedisSubscriber != null) {
            try {
                jedisSubscriber.disconnect();
            } catch (IOException e) {
                logger.warn("failed to disconnect subscriber", e);
            }
        }
    }

    private void disconnectPublisher() {
        if (jedisPublisher != null) {
            try {
                jedisPublisher.disconnect();
            } catch (IOException e) {
                logger.warn("failed to disconnect publisher", e);
            }
        }
    }

}