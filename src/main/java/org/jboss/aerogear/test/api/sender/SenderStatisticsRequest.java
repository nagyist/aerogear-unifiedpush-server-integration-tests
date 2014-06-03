/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.test.api.sender;

import com.google.android.gcm.server.Message;
import com.jayway.awaitility.Awaitility;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.path.json.JsonPath;
import com.jayway.restassured.response.Response;
import org.apache.http.HttpStatus;
import org.jboss.aerogear.test.ContentTypes;
import org.jboss.aerogear.test.Headers;
import org.jboss.aerogear.test.UnexpectedResponseException;
import org.jboss.aerogear.test.api.AbstractSessionRequest;

import java.util.Map;
import java.util.concurrent.Callable;

public class SenderStatisticsRequest extends AbstractSessionRequest<SenderStatisticsRequest> {

    private SenderStatisticsRequest() {
    }

    public SenderStatistics get() {
        Response response = getSession().givenAuthorized()
                .contentType(ContentTypes.json())
                .header(Headers.acceptJson())
                .get("/rest/senderStats");

        UnexpectedResponseException.verifyResponse(response, HttpStatus.SC_OK);

        JsonPath jsonPath = response.jsonPath();

        SenderStatistics statistics = new SenderStatistics();

        if (jsonPath.getJsonObject("gcmMessage") != null) {
            Message.Builder builder = new Message.Builder();

            if (jsonPath.get("gcmMessage.delayWhileIdle") != null) {
                builder.delayWhileIdle(jsonPath.getBoolean("gcmMessage.delayWhileIdle"));
            }
            if (jsonPath.get("gcmMessage.collapseKey") != null) {
                builder.collapseKey(jsonPath.getString("gcmMessage.collapseKey"));
            }
            if (jsonPath.get("gcmMessage.timeToLive") != null) {
                builder.timeToLive(jsonPath.getInt("gcmMessage.timeToLive"));
            }
            Map<String, String> gcmMessageData = jsonPath.getJsonObject("gcmMessage.data");
            for (String key : gcmMessageData.keySet()) {
                builder.addData(key, gcmMessageData.get(key));
            }
            statistics.gcmMessage = builder.build();
        }

        statistics.deviceTokens = jsonPath.getList("deviceTokens");
        statistics.apnsAlert = jsonPath.getString("apnsAlert");
        statistics.apnsBadge = jsonPath.getInt("apnsBadge");
        statistics.apnsCustomFields = jsonPath.getString("apnsCustomFields");
        statistics.apnsSound = jsonPath.getString("apnsSound");
        statistics.apnsExpiry = jsonPath.getLong("apnsExpiry");
        statistics.gcmForChromeAlert = jsonPath.getString("gcmForChromeAlert");

        return statistics;
    }

    public SenderStatistics getAndClear() {
        SenderStatistics statistics = get();

        clear();

        return statistics;
    }

    public void clear() {
        Response response = getSession().givenAuthorized()
                .contentType(ContentTypes.json())
                .header(Headers.acceptJson())
                .delete("/rest/senderStats");

        UnexpectedResponseException.verifyResponse(response, HttpStatus.SC_NO_CONTENT);
    }

    public void await(final int expectedTokenCount, Duration timeout) {
        Awaitility.await().atMost(timeout).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                SenderStatistics statistics = get();
                // FIXME we should report how many tokens we found before failing!
                return statistics.deviceTokens != null && statistics.deviceTokens.size() == expectedTokenCount;
            }
        });
    }

    public SenderStatistics awaitAndGet(int expectedTokenCount, Duration timeout) {
        await(expectedTokenCount, timeout);

        return get();
    }

    public SenderStatistics awaitGetAndClear(int expectedTokenCount, Duration timeout) {
        await(expectedTokenCount, timeout);

        return getAndClear();
    }


    public static SenderStatisticsRequest request() {
        return new SenderStatisticsRequest();
    }


}
