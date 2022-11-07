package com.communication.IncomingCallSample.Controllers;

import com.communication.IncomingCallSample.Logger;
import com.azure.core.util.BinaryData;
import java.util.List;

import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.SystemEventNames;
import com.azure.messaging.eventgrid.systemevents.AcsRecordingFileStatusUpdatedEventData;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationEventData;
import com.azure.messaging.eventgrid.systemevents.SubscriptionValidationResponse;
import com.communication.IncomingCallSample.CallConfiguration;
import com.communication.IncomingCallSample.ConfigurationManager;
import com.communication.IncomingCallSample.IncomingCallHandler;
import com.communication.IncomingCallSample.EventHandler.EventAuthHandler;
import com.communication.IncomingCallSample.EventHandler.EventDispatcher;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IncomingCallController {
	CallConfiguration callConfiguration;

	IncomingCallController(){
		ConfigurationManager configurationManager = ConfigurationManager.getInstance();
		String appBaseUrl = configurationManager.getAppSettings("AppCallBackUri");
		callConfiguration = CallConfiguration.initiateConfiguration(appBaseUrl);
	}

	@PostMapping(value = "/OnIncomingCall", consumes = "application/json", produces = "application/json")
	public ResponseEntity<?> OnIncomingCall(@RequestBody(required = false) String data){
		List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(data);

		if(eventGridEvents.stream().count() > 0)
        {
            EventGridEvent eventGridEvent = eventGridEvents.get(0);
            BinaryData eventData = eventGridEvent.getData();

            if (eventGridEvent.getEventType().equals(SystemEventNames.EVENT_GRID_SUBSCRIPTION_VALIDATION))
            {
                try {
                    SubscriptionValidationEventData subscriptionValidationEvent = eventData.toObject(SubscriptionValidationEventData.class);
                    SubscriptionValidationResponse responseData = new SubscriptionValidationResponse();
                    responseData.setValidationResponse(subscriptionValidationEvent.getValidationCode());

                    return new ResponseEntity<>(responseData, HttpStatus.OK);
                } catch (Exception e){
                    e.printStackTrace();
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
			else if(eventGridEvent.getEventType().equals("Microsoft.Communication.IncomingCall")){
				try {
					String fromParticipant = data.split("\"from\":")[1].split("rawId\":\"")[1].split("\",\"")[0];
					if(data!=null && (fromParticipant == "*" || callConfiguration.acceptCallsFrom.contains(fromParticipant))){
						String incomingCallContext = data.split("\"incomingCallContext\":\"")[1].split("\",\"")[0];
						Logger.logMessage(Logger.MessageType.INFORMATION, incomingCallContext);
						(new IncomingCallHandler(callConfiguration)).report(incomingCallContext);;
					}
				}
				catch(Exception e){
                    e.printStackTrace();
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
			}
			else{
                return new ResponseEntity<>(eventGridEvent.getEventType() + " is not handled.", HttpStatus.BAD_REQUEST);
            }
		}
		return new ResponseEntity<>("Event count is not available.", HttpStatus.BAD_REQUEST);
    }

	@RequestMapping("/api/IncomingCallSample/callback")
	public static String IncomingCallSampleCallBack(@RequestBody(required = false) String data,
			@RequestParam(value = "secret", required = false) String secretKey) {
		EventAuthHandler eventhandler = EventAuthHandler.getInstance();

		/// Validating the incoming request by using secret set in app.settings
		if (eventhandler.authorize(secretKey)) {
			(EventDispatcher.getInstance()).processNotification(data);
		} else {
			Logger.logMessage(Logger.MessageType.ERROR, "Unauthorized Request");
		}
		return "OK";
	}

	@PostMapping(value = "/getRecordingFile", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> getRecordingFile (@RequestBody String eventGridEventJsonData){
        
        Logger.logMessage(Logger.MessageType.INFORMATION,  "Entered getRecordingFile API");

        List<EventGridEvent> eventGridEvents = EventGridEvent.fromString(eventGridEventJsonData);

        if(eventGridEvents.stream().count() > 0)
        {
            EventGridEvent eventGridEvent = eventGridEvents.get(0);
            Logger.logMessage(Logger.MessageType.INFORMATION,  "Event type is --> " + eventGridEvent.getEventType());

            BinaryData eventData = eventGridEvent.getData();
            Logger.logMessage(Logger.MessageType.INFORMATION, "SubscriptionValidationEvent response --> \n" + eventData.toString());

            if (eventGridEvent.getEventType().equals(SystemEventNames.EVENT_GRID_SUBSCRIPTION_VALIDATION))
            {
                try {
                    SubscriptionValidationEventData subscriptionValidationEvent = eventData.toObject(SubscriptionValidationEventData.class);
                    SubscriptionValidationResponse responseData = new SubscriptionValidationResponse();
                    responseData.setValidationResponse(subscriptionValidationEvent.getValidationCode());

                    return new ResponseEntity<>(responseData, HttpStatus.OK);
                } catch (Exception e){
                    e.printStackTrace();
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }

            if(eventGridEvent.getEventType().equals(SystemEventNames.COMMUNICATION_RECORDING_FILE_STATUS_UPDATED)){
                try {
					Logger.logMessage(Logger.MessageType.INFORMATION, "Recording information : " + eventData);
                    return new ResponseEntity<>(true, HttpStatus.OK);
                } 
				catch (Exception e)
				{
                    e.printStackTrace();
                    Logger.logMessage(Logger.MessageType.ERROR, e.getMessage());
                    return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
            else{
                return new ResponseEntity<>(eventGridEvent.getEventType() + " is not handled.", HttpStatus.BAD_REQUEST);
            }
        }

        return new ResponseEntity<>("Event count is not available.", HttpStatus.BAD_REQUEST);
    }
}
