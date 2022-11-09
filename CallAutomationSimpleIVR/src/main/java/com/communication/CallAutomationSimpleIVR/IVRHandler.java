package com.communication.CallAutomationSimpleIVR;

import com.communication.CallAutomationSimpleIVR.Utils.ConfigurationManager;
import com.communication.CallAutomationSimpleIVR.Utils.Logger;
import com.azure.core.util.BinaryData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.azure.communication.callautomation.CallAutomationClientBuilder;
import com.azure.communication.callautomation.CallConnection;
import com.azure.communication.callautomation.EventHandler;
import com.azure.communication.callautomation.CallAutomationClient;
import com.azure.communication.callautomation.models.AddParticipantsOptions;
import com.azure.communication.callautomation.models.AddParticipantsResult;
import com.azure.communication.callautomation.models.AnswerCallOptions;
import com.azure.communication.callautomation.models.AnswerCallResult;
import com.azure.communication.callautomation.models.CallMediaRecognizeOptions;
import com.azure.communication.callautomation.models.DtmfTone;
import com.azure.communication.callautomation.models.FileSource;
import com.azure.communication.callautomation.models.HangUpOptions;
import com.azure.communication.callautomation.models.RecordingStateResult;
import com.azure.communication.callautomation.models.ServerCallLocator;
import com.azure.communication.callautomation.models.StartRecordingOptions;
import com.azure.communication.callautomation.models.CallMediaRecognizeDtmfOptions;
import com.azure.communication.callautomation.models.events.AddParticipantsSucceeded;
import com.azure.communication.callautomation.models.events.CallAutomationEventBase;
import com.azure.communication.callautomation.models.events.CallConnected;
import com.azure.communication.callautomation.models.events.RecognizeCompleted;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.rest.Response;

import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.SystemEventNames;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationEventData;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IVRHandler {
    private static ConfigurationManager configurationManager = ConfigurationManager.getInstance();
    private static CallConnection callConnection = null;
    private static CallAutomationClient callingAutomationClient = new CallAutomationClientBuilder()
            .connectionString(configurationManager.getAppSettings("ConnectionString")).buildClient();

    @PostMapping(value = "/api/incomingCall", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> OnIncomingCall(@RequestBody(required = false) String data) {
        List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(data);

        if (eventGridEvents.stream().count() > 0) {
            EventGridEvent eventGridEvent = eventGridEvents.get(0);
            BinaryData eventData = eventGridEvent.getData();

            if (eventGridEvent.getEventType().equals(SystemEventNames.EVENT_GRID_SUBSCRIPTION_VALIDATION)) {
                try {
                    SubscriptionValidationEventData subscriptionValidationEvent = eventData
                            .toObject(SubscriptionValidationEventData.class);
                    SubscriptionValidationResponse responseData = new SubscriptionValidationResponse();
                    responseData.setValidationResponse(subscriptionValidationEvent.getValidationCode());

                    return new ResponseEntity<>(responseData, HttpStatus.OK);
                } catch (Exception e) {
                    e.printStackTrace();
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            } else if (eventGridEvent.getEventType().equals("Microsoft.Communication.IncomingCall")) {
                try {
                    String callerId = data.split("\"from\":")[1].split("rawId\":\"")[1].split("\",\"")[0].trim();
                    if (data != null) {
                        String incomingCallContext = data.split("\"incomingCallContext\":\"")[1].split("\",\"")[0].trim();
                        Logger.logMessage(Logger.MessageType.INFORMATION, incomingCallContext);

                        var callbackUri = configurationManager.getAppSettings("CallbackUriBase") + "?callerId="
                                + callerId;
                        AnswerCallOptions answerCallOptions = new AnswerCallOptions(incomingCallContext,
                                callbackUri);

                        Response<AnswerCallResult> response = callingAutomationClient
                                .answerCallWithResponse(answerCallOptions, null);
                        AnswerCallResult answerCallResult = response.getValue();

                        callConnection = answerCallResult.getCallConnection();
                        Logger.logMessage(Logger.MessageType.INFORMATION,
                                "AnswerCallWithResponse -- > " + getResponse(response));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            } else {
                return new ResponseEntity<>(eventGridEvent.getEventType() + " is not handled.", HttpStatus.BAD_REQUEST);
            }
        }
        return new ResponseEntity<>("Event count is not available.", HttpStatus.BAD_REQUEST);
    }

    @RequestMapping("/api/callback")
    public static void CallAutomationSimpleIVRCallBack(@RequestBody(required = false) String event,
            @RequestParam(value = "callerId", required = true) String callerId) {

        // 3. Read the event received from Azure Call Automation.
        CallAutomationEventBase callEvent = EventHandler.parseEvent(event);

        if (callEvent instanceof CallConnected) {
            // Start recording
            ServerCallLocator serverCallLocator = new ServerCallLocator(
                    callConnection.getCallProperties().getServerCallId());
            StartRecordingOptions recordingOptions = new StartRecordingOptions(serverCallLocator);
            Response<RecordingStateResult> response = callingAutomationClient.getCallRecording()
                    .startRecordingWithResponse(recordingOptions, null);
            Logger.logMessage(Logger.MessageType.INFORMATION,
                    "Start Recording with recording ID: " + response.getValue().getRecordingId());

            // Start recognizing Dtmf
            String playSource = configurationManager.getAppSettings("CallbackUriBase")
                    + configurationManager.getAppSettings("PromptMessageFile");
            CallMediaRecognizeOptions callMediaRecognizeOptions = new CallMediaRecognizeDtmfOptions(
                    CommunicationIdentifier.fromRawId(callerId), 1)
                    .setInterToneTimeout(Duration.ofSeconds(5))
                    .setInterruptCallMediaOperation(true)
                    .setInitialSilenceTimeout(Duration.ofSeconds(30))
                    .setPlayPrompt(new FileSource().setUri(playSource))
                    .setInterruptPrompt(true);

            Response<Void> startRecognizeResponse = callConnection.getCallMedia()
                    .startRecognizingWithResponse(callMediaRecognizeOptions, null);
            Logger.logMessage(Logger.MessageType.INFORMATION,
                    "Start Recognizing response-- > " + getResponse(startRecognizeResponse));

        } else if (callEvent instanceof RecognizeCompleted) {
            // this RecognizeCompleted correlates to the previous action as per the
            // OperationContext value
            RecognizeCompleted toneReceivedEvent = (RecognizeCompleted) callEvent;
            List<DtmfTone> toneInfo = toneReceivedEvent.getCollectTonesResult().getTones();
            Logger.logMessage(Logger.MessageType.INFORMATION, "Tone received -- > : " + toneInfo);

            if (!toneInfo.isEmpty() && toneInfo.get(0).equals(DtmfTone.ONE)) {
                // Adding participant
                var participant = CommunicationIdentifier
                        .fromRawId(configurationManager.getAppSettings("ParticipantPhoneNumber"));

                List<CommunicationIdentifier> targets = new ArrayList<CommunicationIdentifier>();
                targets.add(participant);

                AddParticipantsOptions addParticipantsOptions = new AddParticipantsOptions(targets);
                addParticipantsOptions.setSourceCallerId(new PhoneNumberIdentifier(callerId));
                addParticipantsOptions.setOperationContext(UUID.randomUUID().toString());
                addParticipantsOptions.setInvitationTimeout(Duration.ofSeconds(30));
                addParticipantsOptions.setRepeatabilityHeaders(null);

                Response<AddParticipantsResult> response = callConnection
                        .addParticipantsWithResponse(addParticipantsOptions, null);
                Logger.logMessage(Logger.MessageType.INFORMATION,
                        "addParticipantWithResponse -- > " + getResponse(response));

                Logger.logMessage(Logger.MessageType.INFORMATION,
                        "addParticipantWithResponse -- > " + getResponse(response));
            } else {
                // Hangup the call
                hangupAsync();
            }
        }
        else if(callEvent instanceof AddParticipantsSucceeded)
        {
            // Hangup the call
            hangupAsync();
        }
    }

    private static void hangupAsync() {
        Logger.logMessage(Logger.MessageType.INFORMATION, "Performing Hangup operation");

        HangUpOptions hangUpOptions = new HangUpOptions(true);
        Response<Void> response = callConnection.hangUpWithResponse(hangUpOptions, null);
        Logger.logMessage(Logger.MessageType.INFORMATION, "hangupWithResponse -- > " + getResponse(response));
    }

    private static String getResponse(Response<?> response) {
        StringBuilder responseString;
        responseString = new StringBuilder("StatusCode: " + response.getStatusCode() + ", Headers: { ");

        for (HttpHeader header : response.getHeaders()) {
            responseString.append(header.getName()).append(":").append(header.getValue()).append(", ");
        }
        responseString.append("} ");
        return responseString.toString();
    }
}
