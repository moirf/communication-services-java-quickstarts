package com.communication.appointmentreminder;

import com.azure.communication.callautomation.CallAutomationClient;
import com.azure.communication.callautomation.CallAutomationClientBuilder;
import com.azure.communication.callautomation.CallConnection;
import com.azure.communication.callautomation.CallMedia;
import com.azure.communication.callautomation.EventHandler;
import com.azure.communication.callautomation.models.CallMediaRecognizeDtmfOptions;
import com.azure.communication.callautomation.models.CallMediaRecognizeOptions;
import com.azure.communication.callautomation.models.CreateCallOptions;
import com.azure.communication.callautomation.models.CreateCallResult;
import com.azure.communication.callautomation.models.DtmfTone;
import com.azure.communication.callautomation.models.FileSource;
import com.azure.communication.callautomation.models.PlayOptions;
import com.azure.communication.callautomation.models.PlaySource;
import com.azure.communication.callautomation.models.events.CallAutomationEventBase;
import com.azure.communication.callautomation.models.events.CallConnected;
import com.azure.communication.callautomation.models.events.PlayCompleted;
import com.azure.communication.callautomation.models.events.PlayFailed;
import com.azure.communication.callautomation.models.events.RecognizeCompleted;
import com.azure.communication.callautomation.models.events.RecognizeFailed;
import com.azure.communication.common.CommunicationIdentifier;
import com.azure.communication.common.CommunicationUserIdentifier;
import com.azure.communication.common.PhoneNumberIdentifier;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.rest.Response;
import com.communication.appointmentreminder.models.CallAutomationClientConfiguration;
import com.communication.appointmentreminder.utitilities.Constants;
import com.communication.appointmentreminder.utitilities.Identity;
import com.communication.appointmentreminder.utitilities.Logger;
import com.communication.appointmentreminder.utitilities.Speech;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
public class AppointmentReminder {
    private static CallAutomationClientConfiguration callConfiguration;
    private static CallAutomationClient callAutomationClient;

    private static CallConnection callConnection;
    private static String targetPhoneNumber;

    /**
     * Sets the configuration needed to act on received events and to start calls.
     */
    public static void setConfiguration() {
        String callBackUrl = ConfigurationManager.getInstance().getAppSettings("CallbackUrl");
        callConfiguration = initiateConfiguration(callBackUrl);
        callAutomationClient = new CallAutomationClientBuilder()
                .connectionString(callConfiguration.getConnectionString())
                .buildClient();
    }

    /**
     * This process starts the Reminder action.
     * @param targetPhoneNumber - Phone Number to call, starting with +1.
     */
    public static void executeReminder(String targetPhoneNumber) {
        try {

            setTargetPhoneNumber(targetPhoneNumber);
            // 1. Preparing data to start the call.
            CommunicationUserIdentifier source = new CommunicationUserIdentifier(callConfiguration.getSourceIdentity());
            List<CommunicationIdentifier> targets = Collections.singletonList(new PhoneNumberIdentifier(targetPhoneNumber));
            CreateCallOptions createCallOption = new CreateCallOptions(source, targets, callConfiguration.getAppCallbackUrl());
            createCallOption.setSourceCallerId(callConfiguration.getSourcePhoneNumber());

            // 2. Create the call.
            Logger.logMessage(Logger.MessageType.INFORMATION,"Performing CreateCall operation");
            Response<CreateCallResult> response = callAutomationClient.createCallWithResponse(createCallOption, null);
            CreateCallResult callResult = response.getValue();

            Logger.logMessage(
                    Logger.MessageType.INFORMATION,
                    "createCallConnectionWithResponse -- > " + getResponse(response) + ", Call connection ID: " +
                            callResult.getCallConnectionProperties().getCallConnectionId()
            );
            Logger.logMessage(
                    Logger.MessageType.INFORMATION,
                    "Call initiated with Call Leg id -- >" +
                            callResult.getCallConnectionProperties().getCallConnectionId());

            callConnection = callResult.getCallConnection();
        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR, "Failure occurred while creating/establishing the call. Exception -- >" + ex.getMessage());
        }
    }

    /**
     * This is the controller that accepts Call Automation Webhook events.
     * @param event - The webhook event emitted by Azure Call Automation.
     */
    @RequestMapping(Constants.CALLBACK_PATH)
    public static void handleIncomingEvents(@RequestBody(required = false) String event) {
        // 3. Read the event received from Azure Call Automation.
        CallAutomationEventBase callEvent = EventHandler.parseEvent(event);
        CallMedia callMedia = callConnection.getCallMedia();
        // 4. When the call has been connected...
        if(callEvent instanceof CallConnected) {
            Logger.logMessage(Logger.MessageType.INFORMATION, "Call successfully connected");
            // 4.1 Prepare the audio file to play as a menu for DTMF recognition.
            PlaySource reminderMessage = new FileSource()
                    .setUri(callConfiguration.getAppBaseUrl() + Speech.getReminderMessage())
                    .setPlaySourceId("ReminderMessage");
            // 4.2 Create the parameters for the recognition.
            CallMediaRecognizeOptions recognizeOptions = new CallMediaRecognizeDtmfOptions(
                    new PhoneNumberIdentifier(targetPhoneNumber),
                    1       // Max number of tones to collect.
            )
                    .setPlayPrompt(reminderMessage)
                    .setOperationContext("ReminderMenu");
            // 4.3 Start recognizing for DTMF tones.
            Response<?> response = callMedia.startRecognizingWithResponse(recognizeOptions, null);
            Logger.logMessage(
                    Logger.MessageType.INFORMATION,
                    "startRecognizingWithResponse -- > " + getResponse(response)
            );
        } else if (callEvent.getOperationContext() != null && callEvent.getOperationContext().equals("ReminderMenu")) {
            if (callEvent instanceof RecognizeCompleted) {
                // 5. Recognize succeeded.
                RecognizeCompleted recognizeCompleted = (RecognizeCompleted) callEvent;
                // 5.1 Collect the tone that was recognized.
                DtmfTone tone = recognizeCompleted.getCollectTonesResult().getTones().get(0);
                Logger.logMessage(Logger.MessageType.INFORMATION, "DTMF tones received: " + tone);
                PlaySource playSource;
                // 5.2 Choose the right sound to play, based on the collected tone.
                if (tone == DtmfTone.ONE) {
                    playSource = new FileSource()
                            .setUri(callConfiguration.getAppBaseUrl() + Speech.getConfirmationMessage())
                            .setPlaySourceId("ConfirmationMessage");
                } else if (tone == DtmfTone.TWO) {
                    playSource = new FileSource()
                            .setUri(callConfiguration.getAppBaseUrl() + Speech.getCancellationMessage())
                            .setPlaySourceId("CancellationMessage");
                } else {
                    playSource = new FileSource()
                            .setUri(callConfiguration.getAppBaseUrl() + Speech.getInvalidInputMessage())
                            .setPlaySourceId("InvalidMessage");
                }
                // 5.3 Play the right sound.
                Response<?> response = callMedia.playToAllWithResponse(playSource, new PlayOptions(), null);
                Logger.logMessage(
                        Logger.MessageType.INFORMATION,
                        "PlayToAllWithResponse -- > " + getResponse(response)
                );
            } else if (callEvent instanceof RecognizeFailed) {
                // 6. In case that the user didn't emit any tone.
                Logger.logMessage(Logger.MessageType.INFORMATION, "Recognize timed out");
                // 6.1 Play the relevant sound.
                PlaySource playSource = new FileSource()
                        .setUri(callConfiguration.getAppBaseUrl() + Speech.getNoInputMessage())
                        .setPlaySourceId("NoInputMessage");
                Response<?> response = callMedia.playToAllWithResponse(playSource, new PlayOptions(), null);
                Logger.logMessage(
                        Logger.MessageType.INFORMATION,
                        "PlayToAllWithResponse -- > " + getResponse(response)
                );
            }
        } else if (callEvent instanceof PlayCompleted || callEvent instanceof PlayFailed) {
            // 7. Once the sound was played. Hang up the call.
            Logger.logMessage(Logger.MessageType.INFORMATION, "Play completed! Terminating the call");
            callConnection.hangUp(true);
        }
    }

    /// <summary>
    /// Fetch configurations from App Settings and create source identity
    /// </summary>
    /// <param name="appBaseUrl">The base url of the app.</param>
    /// <returns>The <c CallConfiguration object.</returns>
    private static CallAutomationClientConfiguration initiateConfiguration(String appBaseUrl) {
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        String connectionString = configurationManager.getAppSettings("Connectionstring");
        String sourcePhoneNumber = configurationManager.getAppSettings("SourcePhone");
        String sourceIdentity = Identity.createUser(connectionString);
        return new CallAutomationClientConfiguration(connectionString, sourceIdentity, sourcePhoneNumber, appBaseUrl);
    }

    private static String getResponse(Response<?> response)
    {
        StringBuilder responseString;
        responseString = new StringBuilder("StatusCode: " + response.getStatusCode() + ", Headers: { ");

        for (HttpHeader header : response.getHeaders()) {
            responseString.append(header.getName()).append(":").append(header.getValue()).append(", ");
        }
        responseString.append("} ");
        return responseString.toString();
    }

    private static void setTargetPhoneNumber(String targetPhoneNumber) {
        AppointmentReminder.targetPhoneNumber = targetPhoneNumber;
    }
}
