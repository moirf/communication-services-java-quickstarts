package com.communication.recognizedtmf.EventHandler;

import com.azure.communication.callingserver.models.events.CallConnectedEvent;
import com.azure.communication.callingserver.models.events.CallDisconnectedEvent;
import com.azure.communication.callingserver.implementation.models.RecognizeCompleted;
import com.azure.communication.callingserver.models.events.CallAutomationEventBase;
import com.azure.communication.callingserver.models.events.PlayCompleted;
import com.azure.communication.callingserver.models.events.PlayFailed;
import com.azure.core.models.CloudEvent;
import com.azure.core.util.BinaryData;
import java.util.*;

public class EventDispatcher {
    private static EventDispatcher instance = null;
    private final Hashtable<String, NotificationCallback> notificationCallbacks;

    EventDispatcher() {
        notificationCallbacks = new Hashtable<>();
    }

    /// <summary>
    /// Get instances of EventDispatcher
    /// </summary>
    public static EventDispatcher getInstance() {
        if (instance == null) {
            instance = new EventDispatcher();
        }
        return instance;
    }

    public boolean subscribe(String eventType, String eventKey, NotificationCallback notificationCallback) {
        String eventId = buildEventKey(eventType, eventKey);
        synchronized (this) {
            return (notificationCallbacks.put(eventId, notificationCallback) == null);
        }
    }

    public void unsubscribe(String eventType, String eventKey) {
        String eventId = buildEventKey(eventType, eventKey);
        synchronized (this) {
            notificationCallbacks.remove(eventId);
        }
    }

    public String buildEventKey(String eventType, String eventKey) {
        return (eventType + "-" + eventKey);
    }

    public void processNotification(String request) {
        CallAutomationEventBase callEvent = this.extractEvent(request);
        callEvent.getCallConnectionId();
        if (callEvent != null) {
            synchronized (this) {
                final NotificationCallback notificationCallback = notificationCallbacks.get(getEventKey(callEvent));
                if (notificationCallback != null) {
                    new Thread(() -> notificationCallback.callback(callEvent)).start();
                }
            }
        }
    }

    public String getEventKey(CallAutomationEventBase callEventBase) {
        if (callEventBase.getClass() == CallConnectedEvent.class) {
            String callLegId = ((CallConnectedEvent) callEventBase).getCallConnectionId();
            return buildEventKey("CallConnected", callLegId);
        }
        else if (callEventBase.getClass() == CallDisconnectedEvent.class) {
            String callLegId = ((CallDisconnectedEvent) callEventBase).getCallConnectionId();
            return buildEventKey("CallDisconnected", callLegId);
        }
        else if (callEventBase.getClass() == RecognizeCompleted.class) {
            String callLegId = ((RecognizeCompleted) callEventBase).getCallConnectionId();
            return buildEventKey("RecognizeCompleted", callLegId);
        } 
        else if (callEventBase.getClass() == PlayCompleted.class) {
            String operationContext = ((PlayCompleted) callEventBase).getOperationContext();
            return buildEventKey("PlayCompleted", operationContext);
        }
        else if (callEventBase.getClass() == PlayFailed.class) {
            String operationContext = ((PlayFailed) callEventBase).getOperationContext();
            return buildEventKey("PlayFailed", operationContext);
        }
        return null;
    }

    public CallAutomationEventBase extractEvent(String content) {
        try {
            List<CloudEvent> cloudEvents = CloudEvent.fromString(content);
            CloudEvent cloudEvent = cloudEvents.get(0);
            BinaryData eventData = cloudEvent.getData();

            if (cloudEvent.getType().equals("CallConnected")) {
                return CallConnectedEvent.deserialize(eventData);
            } else if (cloudEvent.getType().equals("CallDisConnected")) {
                return RecognizeCompleted.deserialize(eventData);
            } else if (cloudEvent.getType().equals("Microsoft.Communication.ToneReceived")) {
                return RecognizeCompleted.deserialize(eventData);
            } else if (cloudEvent.getType().equals("PlayCompleted")) {
                return PlayCompleted.deserialize(eventData);
            } else if (cloudEvent.getType().equals("PlayFailed")) {
                return PlayCompleted.deserialize(eventData);
            }
        } catch (Exception ex) {
            System.out.println("Failed to parse request content Exception: " + ex.getMessage());
        }
        return null;
    }

}
