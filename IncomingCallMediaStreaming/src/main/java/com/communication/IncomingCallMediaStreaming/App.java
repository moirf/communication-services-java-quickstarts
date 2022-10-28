package com.communication.IncomingCallMediaStreaming;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.communication.IncomingCallMediaStreaming.Ngrok.NgrokService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    private static NgrokService ngrokService;
    final static String url = "http://localhost:9007";
    final static String serverPort = "9007";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(App.class);
        application.setDefaultProperties(Collections.singletonMap("server.port", serverPort));
        application.run(args);

        Logger.logMessage(Logger.MessageType.INFORMATION, "Starting ACS Sample App ");

        // Get configuration properties
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        configurationManager.loadAppSettings();

        // Start Ngrok service
        String ngrokUrl = startNgrokService();
        try {
            if (ngrokUrl != null && !ngrokUrl.isEmpty()) {
                Logger.logMessage(Logger.MessageType.INFORMATION,"Server started at -- > " + url);
                Thread runSample = new Thread(() -> runSample(ngrokUrl));
                runSample.start();
                runSample.join();
            } else {
                Logger.logMessage(Logger.MessageType.INFORMATION,"Failed to start Ngrok service");
            }
        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR,"Failed to start Ngrok service -- > " + ex.getMessage());
        }
        Logger.logMessage(Logger.MessageType.INFORMATION, "Press 'Ctrl + C' to exit the sample");
        ngrokService.dispose();
    }

    private static String startNgrokService() {
        try {
            ConfigurationManager configurationManager = ConfigurationManager.getInstance();
            String ngrokPath = configurationManager.getAppSettings("NgrokExePath");

            if (ngrokPath.isEmpty()) {
                Logger.logMessage(Logger.MessageType.INFORMATION, "Ngrok path not provided");
                return null;
            }

            Logger.logMessage(Logger.MessageType.INFORMATION,"Starting Ngrok");
            ngrokService = new NgrokService(ngrokPath, null);

            Logger.logMessage(Logger.MessageType.INFORMATION,"Fetching Ngrok Url");
            String ngrokUrl = ngrokService.getNgrokUrl();

            Logger.logMessage(Logger.MessageType.INFORMATION,"Ngrok Started with url -- > " + ngrokUrl);
            return ngrokUrl;
        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.INFORMATION,"Ngrok service got failed -- > " + ex.getMessage());
            return null;
        }
    }

    private static void runSample(String appBaseUrl) {
        CallConfiguration callConfiguration = initiateConfiguration(appBaseUrl);
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        String outboundCallPairs = configurationManager.getAppSettings("DestinationIdentities");

        try {
            if (outboundCallPairs != null && !outboundCallPairs.isEmpty()) {
                String[] identities = outboundCallPairs.split(";");
                ExecutorService executorService = Executors.newCachedThreadPool();
                Set<Callable<Boolean>> tasks = new HashSet<>();

                for (String identity : identities) {
                    tasks.add(() -> {
                        new IncomingCallMediaStreaming(callConfiguration).report(identity);
                        return true;
                    });
                }
                executorService.invokeAll(tasks);
                executorService.shutdown();
            }
        } catch (Exception ex) {
            Logger.logMessage(Logger.MessageType.ERROR, "Failed to initiate the outbound call Exception -- > " + ex.getMessage());
        }
    }

    /// <summary>
    /// Fetch configurations from App Settings and create source identity
    /// </summary>
    /// <param name="appBaseUrl">The base url of the app.</param>
    /// <returns>The <c CallConfiguration object.</returns>
    private static CallConfiguration initiateConfiguration(String appBaseUrl) {
        ConfigurationManager configurationManager = ConfigurationManager.getInstance();
        String connectionString = configurationManager.getAppSettings("Connectionstring");
        String mediaStreamingTransportURI = configurationManager.getAppSettings("MediaStreamingTransportURI");
        return new CallConfiguration(connectionString, appBaseUrl, mediaStreamingTransportURI);
    }
}
