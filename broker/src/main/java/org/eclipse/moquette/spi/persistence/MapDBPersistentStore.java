/*
 * Copyright (c) 2012-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.moquette.spi.persistence;

import android.util.Log;

import org.eclipse.moquette.proto.MQTTException;
import org.eclipse.moquette.proto.messages.AbstractMessage;
import org.eclipse.moquette.spi.IMatchingCondition;
import org.eclipse.moquette.spi.IMessagesStore;
import org.eclipse.moquette.spi.ISessionsStore;
import org.eclipse.moquette.spi.impl.events.PublishEvent;
import org.eclipse.moquette.spi.impl.storage.StoredPublishEvent;
import org.eclipse.moquette.spi.impl.subscriptions.Subscription;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import static org.eclipse.moquette.server.Server.STORAGE_FILE_PATH;

/**
 * MapDB main persistence implementation
 */
public class MapDBPersistentStore implements IMessagesStore, ISessionsStore {

    private ConcurrentMap<String, StoredMessage> m_retainedStore;
    //maps clientID to the list of pending messages stored
    private ConcurrentMap<String, List<StoredPublishEvent>> m_persistentMessageStore;
    //bind clientID+MsgID -> evt message published
    private ConcurrentMap<String, StoredPublishEvent> m_inflightStore;
    //bind clientID+MsgID -> evt message published
    private ConcurrentMap<String, StoredPublishEvent> m_qos2Store;
    //persistent Map of clientID, set of Subscriptions
    private ConcurrentMap<String, Set<Subscription>> m_persistentSubscriptions;
    private DB m_db;

    @Override
    public void initStore() {
        File tmpFile;
        try {
            tmpFile = new File(STORAGE_FILE_PATH);
            if (tmpFile.exists()) { // XXX: Had to add this section in Android version or it crashes on startup. Feels like it kind of defeats the entire purpose. But I can't figure out if this is a temporary file or actual storage between executions. /Gustav 2014-01-23
                tmpFile.delete();
            }
            tmpFile.createNewFile();
        } catch (IOException ex) {
            Log.e("Moquette", null, ex);
            throw new MQTTException("Can't create temp file for subscriptions storage [" + STORAGE_FILE_PATH + "]", ex);
        }
        m_db = DBMaker.newFileDB(tmpFile)
                .make();
        m_retainedStore = m_db.getHashMap("retained");
        m_persistentMessageStore = m_db.getHashMap("persistedMessages");
        m_inflightStore = m_db.getHashMap("inflight");
        m_persistentSubscriptions = m_db.getHashMap("subscriptions");
        m_qos2Store = m_db.getHashMap("qos2Store");
    }

    @Override
    public void cleanRetained(String topic) {
        m_retainedStore.remove(topic);
    }

    @Override
    public void storeRetained(String topic, ByteBuffer message, AbstractMessage.QOSType qos) {
        if (!message.hasRemaining()) {
            //clean the message from topic
            m_retainedStore.remove(topic);
        } else {
            //store the message to the topic
            byte[] raw = new byte[message.remaining()];
            message.get(raw);
            m_retainedStore.put(topic, new StoredMessage(raw, qos, topic));
        }
        m_db.commit();
    }

    @Override
    public Collection<StoredMessage> searchMatching(IMatchingCondition condition) {
        Log.d("Moquette", "searchMatching scanning all retained messages, presents are " + m_retainedStore.size());

        List<StoredMessage> results = new ArrayList<StoredMessage>();

        for (Map.Entry<String, StoredMessage> entry : m_retainedStore.entrySet()) {
            StoredMessage storedMsg = entry.getValue();
            if (condition.match(entry.getKey())) {
                results.add(storedMsg);
            }
        }

        return results;
    }

    @Override
    public void storePublishForFuture(PublishEvent evt) {
        List<StoredPublishEvent> storedEvents;
        String clientID = evt.getClientID();
        if (!m_persistentMessageStore.containsKey(clientID)) {
            storedEvents = new ArrayList<StoredPublishEvent>();
        } else {
            storedEvents = m_persistentMessageStore.get(clientID);
        }
        storedEvents.add(convertToStored(evt));
        m_persistentMessageStore.put(clientID, storedEvents);
        m_db.commit();
        //NB rewind the evt message content
        Log.d("Moquette", "Stored published message for client " + clientID + " on topic " + evt.getTopic());
    }

    @Override
    public List<PublishEvent> retrievePersistedPublishes(String clientID) {
        List<StoredPublishEvent> storedEvts = m_persistentMessageStore.get(clientID);
        if (storedEvts == null) {
            return null;
        }
        List<PublishEvent> liveEvts = new ArrayList<PublishEvent>();
        for (StoredPublishEvent storedEvt : storedEvts) {
            liveEvts.add(convertFromStored(storedEvt));
        }
        return liveEvts;
    }

    @Override
    public void cleanPersistedPublishMessage(String clientID, int messageID) {
        List<StoredPublishEvent> events = m_persistentMessageStore.get(clientID);
        if (events == null) {
            return;
        }
        StoredPublishEvent toRemoveEvt = null;
        for (StoredPublishEvent evt : events) {
            if (evt.getMessageID() == messageID) {
                toRemoveEvt = evt;
            }
        }
        events.remove(toRemoveEvt);
        m_persistentMessageStore.put(clientID, events);
        m_db.commit();
    }

    public void cleanPersistedPublishes(String clientID) {
        m_persistentMessageStore.remove(clientID);
        m_db.commit();
    }

    public void cleanInFlight(String msgID) {
        m_inflightStore.remove(msgID);
        m_db.commit();
    }

    public void addInFlight(PublishEvent evt, String publishKey) {
        StoredPublishEvent storedEvt = convertToStored(evt);
        m_inflightStore.put(publishKey, storedEvt);
        m_db.commit();
    }

    public void addNewSubscription(Subscription newSubscription, String clientID) {
        Log.d("Moquette", "addNewSubscription invoked with subscription " + newSubscription + " for client " + clientID);
        if (!m_persistentSubscriptions.containsKey(clientID)) {
            Log.d("Moquette", "clientID " + clientID + " is a newcome, creating it's subscriptions set");
            m_persistentSubscriptions.put(clientID, new HashSet<Subscription>());
        }

        Set<Subscription> subs = m_persistentSubscriptions.get(clientID);
        if (!subs.contains(newSubscription)) {
            Log.d("Moquette", "updating clientID " + clientID + " subscriptions set with new subscription");
            //TODO check the subs doesn't contain another subscription to the same topic with different
            Subscription existingSubscription = null;
            for (Subscription scanSub : subs) {
                if (newSubscription.getTopicFilter().equals(scanSub.getTopicFilter())) {
                    existingSubscription = scanSub;
                    break;
                }
            }
            if (existingSubscription != null) {
                subs.remove(existingSubscription);
            }
            subs.add(newSubscription);
            m_persistentSubscriptions.put(clientID, subs);
            Log.d("Moquette", "clientID " + clientID + " subscriptions set now is " + subs);
        }
        m_db.commit();
    }

    public void wipeSubscriptions(String clientID) {
        m_persistentSubscriptions.remove(clientID);
        m_db.commit();
    }

    @Override
    public void updateSubscriptions(String clientID, Set<Subscription> subscriptions) {
        m_persistentSubscriptions.put(clientID, subscriptions);
        m_db.commit();
    }

    public List<Subscription> listAllSubscriptions() {
        List<Subscription> allSubscriptions = new ArrayList<Subscription>();
        for (Map.Entry<String, Set<Subscription>> entry : m_persistentSubscriptions.entrySet()) {
            allSubscriptions.addAll(entry.getValue());
        }
        Log.d("Moquette", "retrieveAllSubscriptions returning subs " + allSubscriptions);
        return allSubscriptions;
    }

    @Override
    public boolean contains(String clientID) {
        return m_persistentSubscriptions.containsKey(clientID);
    }

    public void close() {
        this.m_db.commit();
        Log.d("Moquette", "persisted subscriptions " + m_persistentSubscriptions);
        this.m_db.close();
        Log.d("Moquette", "closed disk storage");
    }

    /*-------- QoS 2  storage management --------------*/
    public void persistQoS2Message(String publishKey, PublishEvent evt) {
        Log.d("Moquette", "persistQoS2Message store pubKey: " + publishKey + ", evt: " + evt);
        m_qos2Store.put(publishKey, convertToStored(evt));
    }

    public void removeQoS2Message(String publishKey) {
        m_qos2Store.remove(publishKey);
    }

    public PublishEvent retrieveQoS2Message(String publishKey) {
        StoredPublishEvent storedEvt = m_qos2Store.get(publishKey);
        return convertFromStored(storedEvt);
    }

    private StoredPublishEvent convertToStored(PublishEvent evt) {
        StoredPublishEvent storedEvt = new StoredPublishEvent(evt);
        return storedEvt;
    }

    private PublishEvent convertFromStored(StoredPublishEvent evt) {
        byte[] message = evt.getMessage();
        ByteBuffer bbmessage = ByteBuffer.wrap(message);
        //bbmessage.flip();
        PublishEvent liveEvt = new PublishEvent(evt.getTopic(), evt.getQos(),
                bbmessage, evt.isRetain(), evt.getClientID(), evt.getMessageID());
        return liveEvt;
    }
}
