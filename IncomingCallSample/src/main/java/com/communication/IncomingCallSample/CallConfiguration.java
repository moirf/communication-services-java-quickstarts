package com.communication.IncomingCallSample;

import com.communication.IncomingCallSample.EventHandler.EventAuthHandler;

public class CallConfiguration {
    public String connectionString;
    public String appBaseUrl;
    public String appCallbackUrl;
    public String targetParticipant;
    public String acceptCallsFrom;
    public String audioFileUri;

    public CallConfiguration(String connectionString, String appBaseUrl, 
    String targetParticipant, String acceptCallsFrom, String audioFileUri) {
        this.connectionString = connectionString;
        this.appBaseUrl = appBaseUrl;
        EventAuthHandler eventhandler = EventAuthHandler.getInstance();
        this.appCallbackUrl = appBaseUrl + "/api/IncomingCallSample/callback?" + eventhandler.getSecretQuerystring();
        this.targetParticipant = targetParticipant;
        this.acceptCallsFrom = acceptCallsFrom;
        this.audioFileUri = audioFileUri;
    }

    public static CallConfiguration initiateConfiguration(String appBaseUrl) {
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        String connectionString = configurationManager.getAppSettings("Connectionstring");
        String targetParticipant = configurationManager.getAppSettings("TargetParticipant");
        String acceptCallsFrom = configurationManager.getAppSettings("AcceptCallsFrom");
        String audioFileUri = configurationManager.getAppSettings("AudioFileUri");
        return new CallConfiguration(connectionString, appBaseUrl, targetParticipant, 
        acceptCallsFrom, audioFileUri );
    }
}