package com.communication.IncomingCallMediaStreaming.WebApp.EventHandler;

import com.azure.communication.callautomation.models.events.CallAutomationEventBase;

public interface NotificationCallback {
    void callback(CallAutomationEventBase callEvent);
}