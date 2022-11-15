package com.communication.IncomingCallMediaStreaming;
import com.communication.IncomingCallMediaStreaming.EventHandler.EventAuthHandler;

public class CallConfiguration {
    public String connectionString;
    public String appBaseUrl;
    public String appCallbackUrl;
    public String acceptCallsFrom;
    public String mediaStreamingTransportURI;

    public CallConfiguration(String connectionString, String appBaseUrl, String acceptCallsFrom, String mediaStreamingTransportURI) {
        this.connectionString = connectionString;
        this.appBaseUrl = appBaseUrl;
        EventAuthHandler eventhandler = EventAuthHandler.getInstance();
        this.appCallbackUrl = appBaseUrl + "/api/IncomingCallMediaStreaming/callback?" + eventhandler.getSecretQuerystring();
        this.acceptCallsFrom = acceptCallsFrom;
        this.mediaStreamingTransportURI = mediaStreamingTransportURI;
    }

    public static CallConfiguration initiateConfiguration(String appBaseUrl) {
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        configurationManager.loadAppSettings();
        String connectionString = configurationManager.getAppSettings("Connectionstring");
        String acceptCallsFrom = configurationManager.getAppSettings("AcceptCallsFrom");
        String mediaStreamingTransportURI = configurationManager.getAppSettings("MediaStreamingTransportURI");
        return new CallConfiguration(connectionString, appBaseUrl, acceptCallsFrom, mediaStreamingTransportURI);
    }
}