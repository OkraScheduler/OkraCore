/*
 * Copyright (c) 2017 Okra Scheduler
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package okra;

import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.client.MongoClient;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.result.DeleteResult;
import okra.base.async.AbstractOkraAsync;
import okra.base.async.OkraAsync;
import okra.base.async.callback.*;
import okra.base.model.OkraItem;
import okra.base.model.OkraStatus;
import okra.index.IndexCreator;
import okra.utils.DateUtil;
import okra.utils.QueryUtil;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Date;

public class OkraSyncImpl<T extends OkraItem> extends AbstractOkraAsync<T> implements OkraAsync<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OkraSyncImpl.class);

    private final Class<T> itemClass;
    private final long defaultHeartbeatExpirationMillis;

    private final MongoClient client;

    public OkraSyncImpl(final MongoClient mongo, final String database,
                        final String collection, final Class<T> itemClass,
                        final long defaultHeartbeatExpirationMillis) {
        super(database, collection);
        this.client = mongo;
        this.itemClass = itemClass;
        this.defaultHeartbeatExpirationMillis = defaultHeartbeatExpirationMillis;
    }

    public void peek(final OkraItemCallback<T> callback) {
        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions();
        options.returnDocument(ReturnDocument.AFTER);

        final Document update = new Document();
        update.put("heartbeat", new Date());
        update.put("status", OkraStatus.PROCESSING.name());

        final SingleResultCallback<Document> mongoCallback = (document, throwable) -> {
            if (throwable == null) {
                callback.onSuccess(documentToOkraItem(document));
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .findOneAndUpdate(QueryUtil.generatePeekQuery(defaultHeartbeatExpirationMillis), update, options, mongoCallback);
    }

    public void poll(final OkraItemCallback<T> callback) {
        final OkraItemCallback<T> pollCallback = new OkraItemCallback<T>() {

            @Override
            public void onSuccess(final T item) {
                delete(item, new OkraItemDeleteCallback() {

                    @Override
                    public void onSuccess(final long deletedCount) {
                        if (deletedCount > 0) {
                            callback.onSuccess(item);
                        }
                    }

                    @Override
                    public void onFailure(final Throwable throwable) {
                        callback.onFailure(throwable);
                    }
                });
            }

            @Override
            public void onFailure(final Throwable throwable) {
                callback.onFailure(throwable);
            }
        };

        peek(pollCallback);
    }

    @Override
    public void setup() {
        IndexCreator.ensureIndexes(this, client, getDatabase(), getCollection());
    }

    @Override
    public void delete(final T item, final OkraItemDeleteCallback callback) {
        final SingleResultCallback<DeleteResult> mongoCallback = (result, throwable) -> {
            if (throwable == null) {
                callback.onSuccess(result.getDeletedCount());
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .deleteOne(new Document("_id", new ObjectId(item.getId())), mongoCallback);
    }

    @Override
    public void reschedule(final T item, final OkraItemOperationCallback<T> callback) {
    }

    @Override
    public void heartbeat(final T item, final OkraItemOperationCallback<T> callback) {
        final Document query = new Document();
        query.put("_id", new ObjectId(item.getId()));
        query.put("status", OkraStatus.PROCESSING.name());
        query.put("heartbeat", DateUtil.toDate(item.getHeartbeat()));

        final Document update = new Document();
        update.put("$set", new Document("heartbeat", new Date()));

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions();
        options.returnDocument(ReturnDocument.AFTER);

        final SingleResultCallback<Document> mongoCallback = (result, throwable) -> {
            if (throwable == null) {
                callback.onSuccess(documentToOkraItem(result));
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .findOneAndUpdate(query, update, options, mongoCallback);
    }

    @Override
    public void schedule(final T item, final OkraItemScheduleCallback callback) {
        final Document document = new Document();

        document.append("status", OkraStatus.PENDING.name());
        document.append("runDate", DateUtil.toDate(item.getRunDate()));

        final SingleResultCallback<Void> mongoCallback = (result, throwable) -> {
            if (throwable == null) {
                callback.onSuccess();
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .insertOne(document, mongoCallback);
    }

    @Override
    public void countByStatus(final OkraStatus status, final OkraCountCallback callback) {
        final Document document = new Document("status", status.name());

        final SingleResultCallback<Long> mongoCallback = (result, throwable) -> {
            if (throwable == null) {
                callback.onSuccess(result);
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .count(document, mongoCallback);
    }

    @Override
    public void countDelayed(final OkraCountCallback callback) {
        final Document document = new Document(
                "runDate",
                new Document("$lt", DateUtil.toDate(LocalDateTime.now()))
        );

        final SingleResultCallback<Long> mongoCallback = (result, throwable) -> {
            if (throwable == null) {
                callback.onSuccess(result);
            } else {
                callback.onFailure(throwable);
            }
        };

        client.getDatabase(getDatabase())
                .getCollection(getCollection())
                .count(document, mongoCallback);
    }

    private T documentToOkraItem(final Document document) {
        if (document == null) {
            return null;
        }

        try {
            final T item = itemClass.newInstance();

            item.setId(document.getObjectId("_id").toString());
            item.setHeartbeat(DateUtil.toLocalDateTime(document.getDate("heartbeat")));
            item.setRunDate(DateUtil.toLocalDateTime(document.getDate("runDate")));
            item.setStatus(OkraStatus.valueOf(document.getString("status")));

            return item;
        } catch (InstantiationException | IllegalAccessException e) {
            LOGGER.error("Error initializing Okra Item instance", e);
        }

        return null;
    }
}