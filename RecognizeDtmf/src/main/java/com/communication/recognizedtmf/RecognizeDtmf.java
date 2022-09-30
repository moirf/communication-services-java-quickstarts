package com.communication.recognizedtmf;

import com.azure.communication.callingserver.CallConnection;
import com.azure.communication.callingserver.CallAutomationClientBuilder;
import com.azure.communication.callingserver.CallAutomationAsyncClient;
import com.azure.communication.callingserver.CallAutomationClient;
import com.azure.communication.callingserver.models.CallConnectionState;
import com.azure.communication.callingserver.models.CreateCallOptions;
import com.azure.communication.callingserver.models.CreateCallResult;
import com.azure.communication.callingserver.models.DtmfConfigurations;
import com.azure.communication.callingserver.models.FileSource;
import com.azure.communication.callingserver.models.PlayOptions;
import com.azure.communication.callingserver.models.PlaySource;
import com.azure.communication.callingserver.models.StopTones;
import com.azure.communication.callingserver.implementation.models.RecognizeCompleted;
import com.azure.communication.callingserver.models.events.CallConnectedEvent;
import com.azure.communication.callingserver.models.events.CallDisconnectedEvent;
import com.azure.communication.callingserver.models.events.PlayCompleted;
import com.azure.communication.callingserver.models.events.PlayFailed;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;

import com.azure.cosmos.implementation.changefeed.CancellationToken;
import com.azure.cosmos.implementation.changefeed.CancellationTokenSource;
import com.communication.recognizedtmf.EventHandler.EventDispatcher;
import com.communication.recognizedtmf.EventHandler.NotificationCallback;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.rest.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RecognizeDtmf {

    private final CallConfiguration callConfiguration;
    private final CallAutomationAsyncClient callingAutomationClient;
    private CallConnection callConnection = null;
    private CancellationTokenSource reportCancellationTokenSource;
    private CancellationToken reportCancellationToken;
    private CompletableFuture<Boolean> callConnectedTask;
    private CompletableFuture<Boolean> playAudioCompletedTask;
    private CompletableFuture<Boolean> callTerminatedTask;
    private CompletableFuture<Boolean> toneReceivedCompleteTask;
    private String toneInputValue = StopTones.ASTERISK.toString();

    public RecognizeDtmf(CallConfiguration callConfiguration) {
        this.callConfiguration = callConfiguration;
        this.callingAutomationClient = new CallAutomationClientBuilder().
        endpoint(this.callConfiguration.connectionString).buildAsyncClient();
    }

    public void report(String targetPhoneNumber) {
        reportCancellationTokenSource = new CancellationTokenSource();
        reportCancellationToken = reportCancellationTokenSource.getToken();

        try {
            createCallAsync(targetPhoneNumber);
            registerToDtmfResultEvent(callConnection.getCallProperties().getCallConnectionId());

            playAudioAsync();
            Boolean playAudioCompleted = playAudioCompletedTask.get();

            if (!playAudioCompleted) {
                hangupAsync();
            } else {
                Boolean toneReceivedComplete = toneReceivedCompleteTask.get();
                if (toneReceivedComplete) {
                    Logger.logMessage(Logger.MessageType.INFORMATION,"Play Audio for input --> " + toneInputValue );
                    playAudioAsInput();
                    hangupAsync();
                } else {
                    hangupAsync();
                }
            }

            // Wait for the call to terminate
            callTerminatedTask.get();
        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR, "Call ended unexpectedly, reason -- > " + ex.getMessage());
        }
    }

    private void createCallAsync(String targetPhoneNumber) {
        try {
            // Preparing request data
            CommunicationUserIdentifier source = new CommunicationUserIdentifier(this.callConfiguration.sourceIdentity);
            List<CommunicationIdentifier> targets = new ArrayList<CommunicationIdentifier>() {
                {add(new PhoneNumberIdentifier(targetPhoneNumber));}
            };

            CreateCallOptions createCallOption = new CreateCallOptions(source, targets, this.callConfiguration.appCallbackUrl);
            createCallOption.setSourceCallerId(this.callConfiguration.sourcePhoneNumber);
            Logger.logMessage(Logger.MessageType.INFORMATION,"Performing CreateCall operation");

            Response<CreateCallResult> response = this.callingAutomationClient.
            createCallWithResponse(createCallOption).block();
            CreateCallResult callResult = response.getValue();
            callConnection = callResult.getCallConnection(); 

            Logger.logMessage(Logger.MessageType.INFORMATION, "createCallConnectionWithResponse -- > " + getResponse(response) + ", Call connection ID: " + callResult.getCallConnectionProperties().getCallConnectionId());
            Logger.logMessage(Logger.MessageType.INFORMATION, "Call initiated with Call Leg id -- >" + callResult.getCallConnectionProperties().getCallConnectionId());

            registerToCallStateChangeEvent(callResult.getCallConnectionProperties().getCallConnectionId());
            callConnectedTask.get();

        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR, "Failure occured while creating/establishing the call. Exception -- >" + ex.getMessage());
        }
    }

    private void registerToCallStateChangeEvent(String callLegId) {
        callTerminatedTask = new CompletableFuture<>();
        callConnectedTask = new CompletableFuture<>();
        // Set the callback method
        NotificationCallback callConnectedNotificaiton = ((callEvent) -> {
            CallConnectedEvent callStateChanged = (CallConnectedEvent) callEvent;
            Logger.logMessage(Logger.MessageType.INFORMATION, "Call State successfully connected");
            callConnectedTask.complete(true);
            EventDispatcher.getInstance().unsubscribe("CallConnected", callLegId);
        });

        NotificationCallback callDisconnectedNotificaiton = ((callEvent) -> {
            CallDisconnectedEvent callStateChanged = (CallDisconnectedEvent) callEvent;
            EventDispatcher.getInstance().unsubscribe("CallDisconnected", callLegId);
            reportCancellationTokenSource.cancel();
            callTerminatedTask.complete(true);
        });

        // Subscribe to the event
        EventDispatcher.getInstance().subscribe("CallConnected", callLegId, callConnectedNotificaiton);
        EventDispatcher.getInstance().subscribe("CallDisconnected", callLegId, callDisconnectedNotificaiton);
    }

    private void registerToDtmfResultEvent(String callLegId) {
        toneReceivedCompleteTask = new CompletableFuture<>();

        NotificationCallback dtmfReceivedEvent = ((callEvent) -> {
            RecognizeCompleted toneReceivedEvent = (RecognizeCompleted) callEvent; //(RecognizeCompleted) callEvent
            List<String> toneInfo = toneReceivedEvent.getCollectTonesResult().getTones();
            Logger.logMessage(Logger.MessageType.INFORMATION, "Tone received -- > : " + toneInfo);

            if (!toneInfo.isEmpty() && toneInfo != null) {
                this.toneInputValue = toneInfo.get(0);
                toneReceivedCompleteTask.complete(true);
            } else {
                toneReceivedCompleteTask.complete(false);
            }
            EventDispatcher.getInstance().unsubscribe("RecognizeCompleted", callLegId);
            // cancel playing audio
            cancelMediaProcessing();
        });
        // Subscribe to event
        EventDispatcher.getInstance().subscribe("RecognizeCompleted", callLegId, dtmfReceivedEvent);
    }

    private void cancelMediaProcessing() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION,"Cancellation request, CancelMediaProcessing will not be performed");
            return;
        }

        Logger.logMessage(Logger.MessageType.INFORMATION, "Performing cancel media processing operation to stop playing audio");
        this.callConnection.getCallMedia().cancelAllMediaOperations();
    }

    private void playAudioAsync() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Cancellation request, PlayAudio will not be performed");
            return;
        }

        try {
            // Preparing data for request
            String audioFileUri = this.callConfiguration.audioFileUrl + this.callConfiguration.audioFileName;
            String appCallbackUri = this.callConfiguration.appCallbackUrl;
            PlayOptions playAudioOptions = new PlayOptions();
            
            playAudioOptions.setLoop(true);
            playAudioOptions.setOperationContext(UUID.randomUUID().toString());

            PlaySource playSource = new FileSource().setUri(audioFileUri);

            Logger.logMessage(Logger.MessageType.INFORMATION, "Performing PlayAudio operation");
            Response<Void> playAudioResponse = this.callConnection.getCallMedia().playToAllWithResponse(playSource, playAudioOptions, null);
            Logger.logMessage(Logger.MessageType.INFORMATION, "playAudioWithResponse -- > " + getResponse(playAudioResponse));

            if (playAudioResponse.getStatusCode() == 202) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Play Audio state running");

                // listen to play audio events
                registerToPlayAudioResultEvent(this.callConnection.getCallProperties().getCallConnectionId());

                CompletableFuture<Boolean> maxWait = CompletableFuture.supplyAsync(() -> {
                    try {
                        TimeUnit.SECONDS.sleep(30);
                    } catch (InterruptedException ex) {
                        Logger.logMessage(Logger.MessageType.ERROR, " -- > " + ex.getMessage());
                    }
                    return false;
                });

                CompletableFuture<Object> completedTask = CompletableFuture.anyOf(playAudioCompletedTask, maxWait);
                if (completedTask.get() != playAudioCompletedTask.get()) {
                    Logger.logMessage(Logger.MessageType.INFORMATION, "No response from user in 30 sec, initiating hangup");
                    playAudioCompletedTask.complete(false);
                    toneReceivedCompleteTask.complete(false);
                }
            }
        } catch (Exception ex) {
            if (playAudioCompletedTask.isCancelled()) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Play audio operation cancelled");
            } else {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Failure occurred while playing audio on the call. Exception: " + ex.getMessage());
            }
        }
    }

    private void playAudioAsInput() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Cancellation request, PlayAudio will not be performed");
            return;
        }

        try {
            // Preparing data for request
            var audioFileName = callConfiguration.InvalidAudioFileName;

            if (toneInputValue == StopTones.ONE.toString())
            {
                audioFileName = callConfiguration.SalesAudioFileName;
            }
            else if (toneInputValue == StopTones.TWO.toString())
            {
                audioFileName = callConfiguration.MarketingAudioFileName;
            }
            else if (toneInputValue == StopTones.THREE.toString())
            {
                audioFileName = callConfiguration.CustomerCareAudioFileName;
            }

            String audioFileUri = this.callConfiguration.audioFileUrl + audioFileName;
            PlaySource playSource = new FileSource().setUri(audioFileUri);
            PlayOptions playAudioOptions = new PlayOptions();
            playAudioOptions.setLoop(false);
            playAudioOptions.setOperationContext(UUID.randomUUID().toString());

            Logger.logMessage(Logger.MessageType.INFORMATION, "Performing PlayAudio operation");
            Response<Void> playAudioResponse = this.callConnection.getCallMedia().
            playToAllWithResponse(playSource, playAudioOptions, null);
            
            Logger.logMessage(Logger.MessageType.INFORMATION, "playAudioWithResponse -- > " + getResponse(playAudioResponse));

            if (playAudioResponse.getStatusCode() == 202) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Play Audio state is running ");

                // listen to play audio events
                registerToPlayAudioResultEvent(this.callConnection.getCallProperties().getCallConnectionId());

                CompletableFuture<Boolean> maxWait = CompletableFuture.supplyAsync(() -> {
                    try {
                        TimeUnit.SECONDS.sleep(30);
                    } catch (InterruptedException ex) {
                        Logger.logMessage(Logger.MessageType.ERROR, " -- > " + ex.getMessage());
                    }
                    return false;
                });

                CompletableFuture<Object> completedTask = CompletableFuture.anyOf(playAudioCompletedTask, maxWait);
                if (completedTask.get() != playAudioCompletedTask.get()) {
                    Logger.logMessage(Logger.MessageType.INFORMATION, "No response from user in 30 sec, initiating hangup");
                    playAudioCompletedTask.complete(false);
                    toneReceivedCompleteTask.complete(false);
                }
            }
        } catch (Exception ex) {
            if (playAudioCompletedTask.isCancelled()) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Play audio operation cancelled");
            } else {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Failure occured while playing audio on the call. Exception: " + ex.getMessage());
            }
        }
    }
    
    private void hangupAsync() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Cancellation request, Hangup will not be performed");
            return;
        }

        Logger.logMessage(Logger.MessageType.INFORMATION, "Performing Hangup operation");
        Response<Void> response = this.callConnection.hangUpWithResponse(true, null);
        Logger.logMessage(Logger.MessageType.INFORMATION, "hangupWithResponse -- > " + getResponse(response));
    }

    private void registerToPlayAudioResultEvent(String callConnectionId) {
        playAudioCompletedTask = new CompletableFuture<>();
        NotificationCallback playCompletedNotification = ((callEvent) -> {
            PlayCompleted playAudioResultEvent = (PlayCompleted) callEvent;
            Logger.logMessage(Logger.MessageType.INFORMATION, "Play audio status completed" );

            EventDispatcher.getInstance().unsubscribe("PlayCompleted", callConnectionId);
            playAudioCompletedTask.complete(true);   
        });

        NotificationCallback playFailedNotification = ((callEvent) -> {
            PlayCompleted playAudioResultEvent = (PlayCompleted) callEvent;
            playAudioCompletedTask.complete(false);
        });

        // Subscribe to event
        EventDispatcher.getInstance().subscribe("PlayCompleted", callConnectionId, playCompletedNotification);
        EventDispatcher.getInstance().subscribe("PlayFailed", callConnectionId, playFailedNotification);
    }

    public String getResponse(Response<?> response)
    {
        StringBuilder responseString;
        responseString = new StringBuilder("StatusCode: " + response.getStatusCode() + ", Headers: { ");

        for (HttpHeader header : response.getHeaders()) {
            responseString.append(header.getName()).append(":").append(header.getValue()).append(", ");
        }
        responseString.append("} ");
        return responseString.toString();
    }
}