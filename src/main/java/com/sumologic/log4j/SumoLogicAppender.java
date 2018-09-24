/**
 *    _____ _____ _____ _____    __    _____ _____ _____ _____
 *   |   __|  |  |     |     |  |  |  |     |   __|     |     |
 *   |__   |  |  | | | |  |  |  |  |__|  |  |  |  |-   -|   --|
 *   |_____|_____|_|_|_|_____|  |_____|_____|_____|_____|_____|
 *
 *                UNICORNS AT WARP SPEED SINCE 2010
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.sumologic.log4j;

import com.sumologic.http.aggregation.SumoBufferFlusher;
import com.sumologic.http.sender.ProxySettings;
import com.sumologic.http.sender.SumoHttpSender;
import com.sumologic.http.queue.BufferWithEviction;
import com.sumologic.http.queue.BufferWithFifoEviction;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;

import java.io.IOException;

import static com.sumologic.http.queue.CostBoundedConcurrentQueue.CostAssigner;

public class SumoLogicAppender extends AppenderSkeleton {

    private String url = null;

    private String proxyHost = null;
    private int proxyPort = -1;
    private String proxyAuth = null;
    private String proxyUser = null;
    private String proxyPassword = null;
    private String proxyDomain = null;


    private int connectionTimeout = 1000;
    private int socketTimeout = 60000;
    private int retryInterval = 10000;        // Once a request fails, how often until we retry.
    private boolean flushAllBeforeStopping = false; // When true, perform a final flush on shutdown

    private long messagesPerRequest = 100;    // How many messages need to be in the queue before we flush
    private long maxFlushInterval = 10000;    // Maximum interval between flushes (ms)
    private long flushingAccuracy = 250;      // How often the flushed thread looks into the message queue (ms)

    private String sourceName = null;
    private String sourceHost = null;
    private String sourceCategory = null;

    private long maxQueueSizeBytes = 1000000;

    private SumoHttpSender sender;
    private SumoBufferFlusher flusher;
    volatile private BufferWithEviction<String> queue;
    private static final String CLIENT_NAME = "log4j-appender";

    // All the parameters

    public String getUrl() { return this.url; }

    public void setUrl(String url) {
        this.url = url;
    }

    public long getMaxQueueSizeBytes() { return this.maxQueueSizeBytes; }

    public void setMaxQueueSizeBytes(long maxQueueSizeBytes) {
        this.maxQueueSizeBytes = maxQueueSizeBytes;
    }

    public long getMessagesPerRequest() { return this.messagesPerRequest; }

    public void setMessagesPerRequest(long messagesPerRequest) {
        this.messagesPerRequest = messagesPerRequest;
    }

    public long getMaxFlushInterval() { return this.maxFlushInterval; }

    public void setMaxFlushInterval(long maxFlushInterval) {
        this.maxFlushInterval = maxFlushInterval;
    }

    public String getSourceName() { return this.sourceName; }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceHost() { return this.sourceHost; }

    public void setSourceHost(String sourceHost) {
        this.sourceHost = sourceHost;
    }

    public String getSourceCategory() { return this.sourceCategory; }

    public void setSourceCategory(String sourceCategory) {
        this.sourceCategory = sourceCategory;
    }

    public long getFlushingAccuracy() { return this.flushingAccuracy; }

    public void setFlushingAccuracy(long flushingAccuracy) {
        this.flushingAccuracy = flushingAccuracy;
    }

    public int getConnectionTimeout() { return this.connectionTimeout; }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getSocketTimeout() { return this.socketTimeout; }

    public void setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public int getRetryInterval() { return this.retryInterval; }

    public void setRetryInterval(int retryInterval) {
        this.retryInterval = retryInterval;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyAuth() {
        return proxyAuth;
    }

    public void setProxyAuth(String proxyAuth) {
        this.proxyAuth = proxyAuth;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public void setProxyUser(String proxyUser) {
        this.proxyUser = proxyUser;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    public String getProxyDomain() {
        return proxyDomain;
    }

    public void setProxyDomain(String proxyDomain) {
        this.proxyDomain = proxyDomain;
    }

    public boolean getFlushAllBeforeStopping() {
        return flushAllBeforeStopping;
    }

    public void setFlushAllBeforeStopping(boolean flushAllBeforeStopping) {
        this.flushAllBeforeStopping = flushAllBeforeStopping;
    }

    @Override
    public void activateOptions() {
        LogLog.debug("Activating options");

        /* Initialize queue */
        if (queue == null) {
            queue = new BufferWithFifoEviction<String>(maxQueueSizeBytes, new CostAssigner<String>() {
                @Override
                public long cost(String e) {
                    // Note: This is only an estimate for total byte usage, since in UTF-8 encoding,
                    // the size of one character may be > 1 byte.
                    return e.length();
                }
            });
        } else {
            queue.setCapacity(maxQueueSizeBytes);
        }

        /* Initialize sender */
        if (sender == null)
            sender = new SumoHttpSender();

        sender.setRetryInterval(retryInterval);
        sender.setConnectionTimeout(connectionTimeout);
        sender.setSocketTimeout(socketTimeout);
        sender.setUrl(url);
        sender.setSourceHost(sourceHost);
        sender.setSourceName(sourceName);
        sender.setSourceCategory(sourceCategory);
        sender.setProxySettings(new ProxySettings(
                proxyHost,
                proxyPort,
                proxyAuth,
                proxyUser,
                proxyPassword,
                proxyDomain));
        sender.setClientHeaderValue(CLIENT_NAME);
        sender.init();

        /* Initialize flusher  */
        if (flusher != null)
            flusher.stop();

        flusher = new SumoBufferFlusher(flushingAccuracy,
                messagesPerRequest,
                maxFlushInterval,
                sender,
                queue,
                flushAllBeforeStopping);
        flusher.start();

    }

    @Override
    protected void append(LoggingEvent event) {
        if (!checkEntryConditions()) {
            LogLog.warn("Appender not initialized. Dropping log entry");
            return;
        }

        StringBuilder builder = new StringBuilder(1024);
        builder.append(layout.format(event));
        if (layout.ignoresThrowable()) {
            String[] throwableStrRep = event.getThrowableStrRep();
            if (throwableStrRep != null) {
                for (String line : throwableStrRep) {
                    builder.append(line);
                    builder.append(Layout.LINE_SEP);
                }
            }
        }

        try {
            queue.add(builder.toString());
        } catch (Exception e) {
            LogLog.error("Unable to insert log entry into log queue. ", e);
        }
    }

    @Override
    public void close() {
        LogLog.debug("Closing SumoLogicAppender : " + getName());
        try {
            flusher.stop();
            flusher = null;

            sender.close();
            sender = null;
        } catch (IOException e) {
            LogLog.error("Unable to close appender", e);
        }
    }

    @Override
    public boolean requiresLayout() {
        return true;
    }

    // Private bits.

    private boolean checkEntryConditions() {
        return sender != null && sender.isInitialized();
    }

}
