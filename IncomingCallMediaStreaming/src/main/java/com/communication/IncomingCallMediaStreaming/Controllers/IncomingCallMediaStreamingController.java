package com.communication.IncomingCallMediaStreaming.Controllers;

import com.communication.IncomingCallMediaStreaming.Logger;
import com.communication.IncomingCallMediaStreaming.EventHandler.EventAuthHandler;
import com.communication.IncomingCallMediaStreaming.EventHandler.EventDispatcher;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IncomingCallMediaStreamingController {

	@RequestMapping("/api/IncomingCallMediaStreaming/callback")
	public static String onIncomingRequestAsync(@RequestBody(required = false) String data,
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
}
