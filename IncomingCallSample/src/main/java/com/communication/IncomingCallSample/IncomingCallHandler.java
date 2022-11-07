package com.communication.IncomingCallSample;

import com.azure.communication.callautomation.CallAutomationClientBuilder;
import com.azure.communication.callautomation.CallConnection;
import com.azure.communication.callautomation.CallAutomationClient;
import com.azure.communication.callautomation.models.AnswerCallOptions;
import com.azure.communication.callautomation.models.AnswerCallResult;
import com.azure.communication.callautomation.models.CallLocator;
import com.azure.communication.callautomation.models.CallMediaRecognizeOptions;
import com.azure.communication.callautomation.models.DtmfTone;
import com.azure.communication.callautomation.models.FileSource;
import com.azure.communication.callautomation.models.HangUpOptions;
import com.azure.communication.callautomation.models.PlaySource;
import com.azure.communication.callautomation.models.RecordingStateResult;
import com.azure.communication.callautomation.models.ServerCallLocator;
import com.azure.communication.callautomation.models.StartRecordingOptions;
import com.azure.communication.callautomation.models.CallMediaRecognizeDtmfOptions;
import com.azure.communication.callautomation.models.events.CallConnectedEvent;
import com.azure.communication.callautomation.models.events.CallDisconnectedEvent;
import com.azure.communication.callautomation.models.events.PlayCanceledEvent;
import com.azure.communication.callautomation.models.events.PlayCompletedEvent;
import com.azure.communication.callautomation.models.events.PlayFailedEvent;
import com.azure.communication.callautomation.models.events.RecognizeCompletedEvent;
import com.azure.communication.callautomation.models.events.RecognizeFailedEvent;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.cosmos.implementation.changefeed.CancellationToken;
import com.azure.cosmos.implementation.changefeed.CancellationTokenSource;
import com.communication.IncomingCallSample.EventHandler.EventDispatcher;
import com.communication.IncomingCallSample.EventHandler.NotificationCallback;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.rest.Response;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

enum CommunicationIdentifierKind {
    UserIdentity, PhoneIdentity, UnknownIdentity
}

public class IncomingCallHandler {

    private final CallConfiguration callConfiguration;
    private final CallAutomationClient callingAutomationClient;
    private CallConnection callConnection = null;
    private CancellationTokenSource reportCancellationTokenSource;
    private CancellationToken reportCancellationToken;
    private CompletableFuture<Boolean> callConnectedTask;
    private CompletableFuture<Boolean> callTerminatedTask;
    private CompletableFuture<Boolean> playAudioCompletedTask;
    private CompletableFuture<Boolean> toneReceivedCompleteTask;

    public IncomingCallHandler(CallConfiguration callConfiguration) {
        this.callConfiguration = callConfiguration;
        this.callingAutomationClient = new CallAutomationClientBuilder().connectionString(this.callConfiguration.connectionString)
        .buildClient();
    }

    public void report(String incomingCallContext) {
        reportCancellationTokenSource = new CancellationTokenSource();
        reportCancellationToken = reportCancellationTokenSource.getToken();
        try {
            AnswerCallOptions answerCallOptions = new AnswerCallOptions(incomingCallContext, this.callConfiguration.appCallbackUrl);
            
            Response<AnswerCallResult> response = this.callingAutomationClient.answerCallWithResponse(answerCallOptions, null);
            AnswerCallResult answerCallResult = response.getValue();

            callConnection = answerCallResult.getCallConnection();
            Logger.logMessage(Logger.MessageType.INFORMATION, "AnswerCallWithResponse -- > " + getResponse(response));
            
            registerToCallStateChangeEvent(callConnection.getCallProperties().getCallConnectionId());

            //Wait for the call to get connected
            callConnectedTask.get();
            registerToDtmfResultEvent(callConnection.getCallProperties().getCallConnectionId());
            startRecognizingDtmf();
            var playAudioCompleted = playAudioCompletedTask.get();

            if (playAudioCompleted)
            {
                toneReceivedCompleteTask.get();
            }
            hangupAsync();
            
            // Wait for the call to terminate
            callTerminatedTask.get();

        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR, "Call ended unexpectedly, reason -- > " + ex.getMessage());
        }
    }

    private void registerToCallStateChangeEvent(String callLegId) {
        callTerminatedTask = new CompletableFuture<>();
        callConnectedTask = new CompletableFuture<>();
        // Set the callback method
        NotificationCallback callConnectedNotificaiton = ((callEvent) -> {
        Logger.logMessage(Logger.MessageType.INFORMATION, "Call State successfully connected");

        //Start recording
        ServerCallLocator serverCallLocator = new ServerCallLocator(callConnection.getCallProperties().getServerCallId());
        StartRecordingOptions recordingOptions = new StartRecordingOptions(serverCallLocator);
        Response<RecordingStateResult> response = this.callingAutomationClient.getCallRecording().startRecordingWithResponse(recordingOptions, null);
        Logger.logMessage(Logger.MessageType.INFORMATION, "Start Recording with recording ID: " + response.getValue().getRecordingId());
        
        callConnectedTask.complete(true);
        EventDispatcher.getInstance().unsubscribe(CallConnectedEvent.class.getName(), callLegId);
        });

        NotificationCallback callDisconnectedNotificaiton = ((callEvent) -> {
            EventDispatcher.getInstance().unsubscribe(CallDisconnectedEvent.class.getName(), callLegId);
            reportCancellationTokenSource.cancel();
            callTerminatedTask.complete(true);
        });

        // Subscribe to the event
        EventDispatcher.getInstance().subscribe(CallConnectedEvent.class.getName(), callLegId, callConnectedNotificaiton);
        EventDispatcher.getInstance().subscribe(CallDisconnectedEvent.class.getName(), callLegId, callDisconnectedNotificaiton);
    }

    private void registerToDtmfResultEvent(String callLegId) {
        toneReceivedCompleteTask = new CompletableFuture<>();

        NotificationCallback dtmfReceivedEvent = ((callEvent) -> {
            RecognizeCompletedEvent toneReceivedEvent = (RecognizeCompletedEvent) callEvent;
            List<DtmfTone> toneInfo = toneReceivedEvent.getCollectTonesResult().getTones();
            Logger.logMessage(Logger.MessageType.INFORMATION, "Tone received -- > : " + toneInfo);

            if (!toneInfo.isEmpty() && toneInfo.get(0).equals(DtmfTone.ONE)) {
                toneReceivedCompleteTask.complete(true);
            } else {
                toneReceivedCompleteTask.complete(false);
            }
            EventDispatcher.getInstance().unsubscribe(RecognizeCompletedEvent.class.getName(), callLegId);   
            playAudioCompletedTask.complete(true);
            // cancel playing audio
            cancelMediaProcessing();
        });

        NotificationCallback dtmfFailedEvent = ((callEvent) -> {
            EventDispatcher.getInstance().unsubscribe(RecognizeFailedEvent.class.getName(), callLegId);
            toneReceivedCompleteTask.complete(false);
            playAudioCompletedTask.complete(false);
        });

        // Subscribe to event
        EventDispatcher.getInstance().subscribe(RecognizeCompletedEvent.class.getName(), callLegId, dtmfReceivedEvent);
        EventDispatcher.getInstance().subscribe(RecognizeFailedEvent.class.getName(), callLegId, dtmfFailedEvent);
    }

    private void cancelMediaProcessing() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION,"Cancellation request, CancelMediaProcessing will not be performed");
            return;
        }

        Logger.logMessage(Logger.MessageType.INFORMATION, "Performing cancel media processing operation to stop playing audio");
        this.callConnection.getCallMedia().cancelAllMediaOperations();
    }

    private void startRecognizingDtmf() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Cancellation request, PlayAudio will not be performed");
            return;
        }

        try {
            // Preparing data for request
            PlaySource playSource = new FileSource().setUri(this.callConfiguration.audioFileUri);

            // listen to play audio events
            registerToPlayAudioResultEvent(this.callConnection.getCallProperties().getCallConnectionId());

            CommunicationIdentifier targetPhoneNumber = null;
            var identifierKind = getIdentifierKind(this.callConfiguration.targetParticipant);

            if(identifierKind == CommunicationIdentifierKind.UnknownIdentity)
            {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Unknown identity provided. Enter valid phone number or communication user id");
                playAudioCompletedTask.complete(false);
                toneReceivedCompleteTask.complete(false);
            }
            else if(identifierKind == CommunicationIdentifierKind.PhoneIdentity)
            {
                targetPhoneNumber = new PhoneNumberIdentifier(callConfiguration.targetParticipant);
            }
            else if(identifierKind == CommunicationIdentifierKind.UserIdentity)
            {
                targetPhoneNumber = new CommunicationUserIdentifier(callConfiguration.targetParticipant);
            }
            //Start recognizing Dtmf Tone
            CallMediaRecognizeOptions callMediaRecognizeOptions = 
            new CallMediaRecognizeDtmfOptions(targetPhoneNumber, 1)
            .setInterToneTimeout(Duration.ofSeconds(5))
            .setInterruptCallMediaOperation(true)
            .setInitialSilenceTimeout(Duration.ofSeconds(30))
            .setPlayPrompt(playSource)
            .setInterruptPrompt(true);
            
            Response<Void> startRecognizeResponse = this.callConnection.getCallMedia().startRecognizingWithResponse(callMediaRecognizeOptions, null);
            Logger.logMessage(Logger.MessageType.INFORMATION, "Start Recognizing response-- > " + getResponse(startRecognizeResponse));

            //Wait for 30 secs for input
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
        } catch (Exception ex) {
            if (playAudioCompletedTask.isCancelled()) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Start Recognizing with play audio prompt got cancelled");
            } else {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Failure occurred while start recognizing with Play audio prompt on the call. Exception: " + ex.getMessage());
            }
        }
    }

    private void hangupAsync() {
        if (reportCancellationToken.isCancellationRequested()) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Cancellation request, Hangup will not be performed");
            return;
        }

        Logger.logMessage(Logger.MessageType.INFORMATION, "Performing Hangup operation");

        HangUpOptions hangUpOptions = new HangUpOptions(true);
        Response<Void> response = this.callConnection.hangUpWithResponse(hangUpOptions, null);
        Logger.logMessage(Logger.MessageType.INFORMATION, "hangupWithResponse -- > " + getResponse(response));
    }

    private void registerToPlayAudioResultEvent(String callConnectionId) {
        playAudioCompletedTask = new CompletableFuture<>();
        NotificationCallback playCompletedNotification = ((callEvent) -> {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Play audio status completed" );

            EventDispatcher.getInstance().unsubscribe(PlayCompletedEvent.class.getName(), callConnectionId);
            playAudioCompletedTask.complete(true);   
        });

        NotificationCallback playFailedNotification = ((callEvent) -> {
            EventDispatcher.getInstance().unsubscribe(PlayFailedEvent.class.getName(), callConnectionId);
            reportCancellationTokenSource.cancel();
            playAudioCompletedTask.complete(false);
        });

        NotificationCallback playCanceledNotification = ((callEvent) -> {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Play audio status Canceled" );
            EventDispatcher.getInstance().unsubscribe(PlayCanceledEvent.class.getName(), callConnectionId);
            reportCancellationTokenSource.cancel();
            playAudioCompletedTask.complete(false);
        });

        // Subscribe to event
        EventDispatcher.getInstance().subscribe(PlayCompletedEvent.class.getName(), callConnectionId, playCompletedNotification);
        EventDispatcher.getInstance().subscribe(PlayFailedEvent.class.getName(), callConnectionId, playFailedNotification);
        EventDispatcher.getInstance().subscribe(PlayCanceledEvent.class.getName(), callConnectionId, playCanceledNotification);
    }

    public final String userIdentityRegex = "8:acs:[0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12}_[0-9a-fA-F]{8}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{4}\\-[0-9a-fA-F]{12}";
    public final String phoneIdentityRegex = "^\\+\\d{10,14}$";

    private CommunicationIdentifierKind getIdentifierKind(String participantnumber) {
        // checks the identity type returns as string
        return ((Pattern.matches(userIdentityRegex, participantnumber)) ? CommunicationIdentifierKind.UserIdentity
                : (Pattern.matches(phoneIdentityRegex, participantnumber)) ? CommunicationIdentifierKind.PhoneIdentity
                        : CommunicationIdentifierKind.UnknownIdentity);
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