import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.api.montoya.ui.editor.HttpRequestEditor;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Extension implements BurpExtension {
    // Global variable to store captured headers (header name -> value)
    private final Map<String, String> capturedHeaders = new HashMap<>();
    
    // Text areas for configuration
    private JTextArea urlsTextArea;
    private JTextArea headersTextArea;
    private JTextArea capturedHeadersDisplay;
    
    // Toggle for enabling/disabling header modification
    private JCheckBox enableModificationCheckbox;
    
    @Override
    public void initialize(MontoyaApi montoyaApi) {
        montoyaApi.extension().setName("Proxy Header Grabber");
        montoyaApi.logging().logToOutput("Extension loaded successfully.");
        
        // Create and register custom Suite Tab with configuration UI
        montoyaApi.userInterface().registerSuiteTab("Proxy Header Grabber", createConfigPanel());
        
        // Register proxy handler to intercept requests
        montoyaApi.proxy().registerRequestHandler(new ProxyRequestHandler() {
            @Override
            public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest interceptedRequest) {
                // Continue without modification at received stage
                return ProxyRequestReceivedAction.continueWith(interceptedRequest);
            }

            @Override
            public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest interceptedRequest) {
                String requestUrl = interceptedRequest.url();
                
                // Get URLs and headers from settings
                List<String> observedUrls = getConfiguredUrls();
                List<String> headersToCapture = getConfiguredHeaders();
                
                // Check if this URL matches any of our observed URLs
                for (String observedUrl : observedUrls) {
                    if (requestUrl.contains(observedUrl.trim())) {
                        // Capture all configured headers
                        boolean anyHeaderFound = false;
                        StringBuilder logMessage = new StringBuilder("[Headers Found]\nURL: " + requestUrl + "\n");
                        
                        for (String headerName : headersToCapture) {
                            String headerValue = interceptedRequest.headerValue(headerName.trim());
                            if (headerValue != null && !headerValue.isEmpty()) {
                                capturedHeaders.put(headerName.trim(), headerValue);
                                logMessage.append(headerName.trim()).append(": ").append(headerValue).append("\n");
                                anyHeaderFound = true;
                            }
                        }
                        
                        if (anyHeaderFound) {
                            logMessage.append("Headers stored for Repeater use.\n---");
                            montoyaApi.logging().logToOutput(logMessage.toString());
                            // Update the display
                            updateCapturedHeadersDisplay();
                        } else {
                            montoyaApi.logging().logToOutput(
                                "[No Configured Headers Found]\n" +
                                "URL: " + requestUrl + "\n" +
                                "---"
                            );
                        }
                        break; // Found a match, no need to check other URLs
                    }
                }
                
                // Continue sending the request without modification
                return ProxyRequestToBeSentAction.continueWith(interceptedRequest);
            }
        });
        
        // Register HTTP handler for Repeater to actually replace headers when sending
        montoyaApi.http().registerHttpHandler(new HttpHandler() {
            @Override
            public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
                // Check if header modification is enabled - if not, pass through unmodified
                if (enableModificationCheckbox == null || !enableModificationCheckbox.isSelected()) {
                    return RequestToBeSentAction.continueWith(requestToBeSent);
                }
                
                // Only process requests from Repeater tool
                if (requestToBeSent.toolSource().isFromTool(ToolType.REPEATER)) {
                    // Check if the URL is in scope
                    if (!montoyaApi.scope().isInScope(requestToBeSent.url())) {
                        return RequestToBeSentAction.continueWith(requestToBeSent);
                    }
                    
                    // If we have captured headers, replace them
                    if (!capturedHeaders.isEmpty()) {
                        HttpRequest modifiedRequest = requestToBeSent;
                        StringBuilder logMessage = new StringBuilder("[Repeater] Replaced headers for in-scope URL: " + requestToBeSent.url() + "\n");
                        
                        for (Map.Entry<String, String> header : capturedHeaders.entrySet()) {
                            modifiedRequest = modifiedRequest.withUpdatedHeader(header.getKey(), header.getValue());
                            logMessage.append("  ").append(header.getKey()).append(": ").append(header.getValue()).append("\n");
                        }
                        
                        montoyaApi.logging().logToOutput(logMessage.toString());
                        return RequestToBeSentAction.continueWith(modifiedRequest);
                    }
                }
                // Continue with original request
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            @Override
            public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
                // No processing needed for responses
                return ResponseReceivedAction.continueWith(responseReceived);
            }
        });
        
        // Register custom editor tab that shows the request with updated Authorization header
        montoyaApi.userInterface().registerHttpRequestEditorProvider(new HttpRequestEditorProvider() {
            @Override
            public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
                return new AutoModifiedEditor(montoyaApi, creationContext);
            }
        });
        
        montoyaApi.logging().logToOutput("Proxy Header Grabber initialized.");
        montoyaApi.logging().logToOutput("Custom tab registered - configure URLs and headers in the 'Proxy Header Grabber' tab.");
        montoyaApi.logging().logToOutput("HTTP handler registered - will modify requests sent from Repeater.");
        montoyaApi.logging().logToOutput("Custom 'Auto-modified' editor tab registered for Repeater.");
        montoyaApi.logging().logToOutput("The tab shows what will be sent with updated headers for IN-SCOPE URLs.");
    }
    
    // Create the configuration panel UI
    private JPanel createConfigPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Top panel with title and enable checkbox
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        
        JLabel titleLabel = new JLabel("Configuration");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 16));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(titleLabel);
        
        topPanel.add(Box.createVerticalStrut(10)); // Add spacing
        
        // Enable/disable checkbox
        enableModificationCheckbox = new JCheckBox("Replace headers in Repeater", true);
        enableModificationCheckbox.setFont(new Font(enableModificationCheckbox.getFont().getName(), Font.BOLD, 14));
        enableModificationCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(enableModificationCheckbox);
        
        topPanel.add(Box.createVerticalStrut(10)); // Add spacing after checkbox
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel with configuration
        JPanel centerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(5, 5, 5, 5);
        
        // URLs section
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.weighty = 0;
        JLabel urlsLabel = new JLabel("Monitored URLs -  these are used for grabbing headers from (one per line):");
        centerPanel.add(urlsLabel, gbc);
        
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 0.4;
        urlsTextArea = new JTextArea("https://www.example.com", 8, 50);
        urlsTextArea.setLineWrap(true);
        urlsTextArea.setWrapStyleWord(true);
        JScrollPane urlsScrollPane = new JScrollPane(urlsTextArea);
        centerPanel.add(urlsScrollPane, gbc);
        
        // Headers section
        gbc.gridy = 2;
        gbc.weighty = 0;
        JLabel headersLabel = new JLabel("Headers to grab (one per line):");
        centerPanel.add(headersLabel, gbc);
        
        gbc.gridy = 3;
        gbc.weighty = 0.3;
        headersTextArea = new JTextArea("Authorization", 5, 50);
        headersTextArea.setLineWrap(true);
        headersTextArea.setWrapStyleWord(true);
        JScrollPane headersScrollPane = new JScrollPane(headersTextArea);
        centerPanel.add(headersScrollPane, gbc);
        
        // Captured headers display section
        gbc.gridy = 4;
        gbc.weighty = 0;
        JLabel capturedLabel = new JLabel("Currently Captured Headers:");
        capturedLabel.setFont(new Font(capturedLabel.getFont().getName(), Font.BOLD, 12));
        centerPanel.add(capturedLabel, gbc);
        
        gbc.gridy = 5;
        gbc.weighty = 0.3;
        capturedHeadersDisplay = new JTextArea("No headers captured yet.\n\nHeaders will appear here when captured from proxy traffic.", 6, 50);
        capturedHeadersDisplay.setEditable(false);
        capturedHeadersDisplay.setFont(new Font("Monospaced", Font.PLAIN, 12));
        capturedHeadersDisplay.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        JScrollPane capturedScrollPane = new JScrollPane(capturedHeadersDisplay);
        centerPanel.add(capturedScrollPane, gbc);
        
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        
        return mainPanel;
    }
    
    // Helper method to get configured URLs from text area
    private List<String> getConfiguredUrls() {
        if (urlsTextArea == null) {
            return Arrays.asList();
        }
        String urlsConfig = urlsTextArea.getText();
        if (urlsConfig == null || urlsConfig.trim().isEmpty()) {
            return Arrays.asList();
        }
        // Split by newlines
        return Arrays.stream(urlsConfig.split("\\r?\\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
    
    // Helper method to get configured headers from text area
    private List<String> getConfiguredHeaders() {
        if (headersTextArea == null) {
            return Arrays.asList();
        }
        String headersConfig = headersTextArea.getText();
        if (headersConfig == null || headersConfig.trim().isEmpty()) {
            return Arrays.asList();
        }
        // Split by newlines
        return Arrays.stream(headersConfig.split("\\r?\\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
    
    // Update the captured headers display (thread-safe)
    private void updateCapturedHeadersDisplay() {
        SwingUtilities.invokeLater(() -> {
            if (capturedHeadersDisplay != null) {
                if (capturedHeaders.isEmpty()) {
                    capturedHeadersDisplay.setText("No headers captured yet.\n\nHeaders will appear here when captured from proxy traffic.");
                } else {
                    capturedHeadersDisplay.setText(formatCapturedHeaders());
                }
            }
        });
    }
    
    // Format captured headers for display
    private String formatCapturedHeaders() {
        StringBuilder sb = new StringBuilder();
        sb.append("Captured Headers (").append(capturedHeaders.size()).append(" total):\n");
        sb.append("─".repeat(60)).append("\n\n");
        
        for (Map.Entry<String, String> entry : capturedHeaders.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        return sb.toString();
    }
    
    // Custom editor tab that shows the modified request with updated Authorization header
    private class AutoModifiedEditor implements ExtensionProvidedHttpRequestEditor {
        private final MontoyaApi api;
        private final HttpRequestEditor requestEditor;
        private final EditorCreationContext creationContext;
        private HttpRequestResponse requestResponse;
        
        public AutoModifiedEditor(MontoyaApi api, EditorCreationContext creationContext) {
            this.api = api;
            this.creationContext = creationContext;
            this.requestEditor = api.userInterface().createHttpRequestEditor();
        }
        
        @Override
        public HttpRequest getRequest() {
            return requestEditor.getRequest();
        }
        
        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.requestResponse = requestResponse;
            
            // Get the original request
            HttpRequest originalRequest = requestResponse.request();
            
            // If we have captured headers and the URL is in scope, update them
            if (!capturedHeaders.isEmpty() && api.scope().isInScope(originalRequest.url())) {
                HttpRequest modifiedRequest = originalRequest;
                for (Map.Entry<String, String> header : capturedHeaders.entrySet()) {
                    modifiedRequest = modifiedRequest.withUpdatedHeader(header.getKey(), header.getValue());
                }
                requestEditor.setRequest(modifiedRequest);
            } else {
                // Show original request
                requestEditor.setRequest(originalRequest);
            }
        }
        
        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            // Only show this tab for Repeater, when we have captured headers, and URL is in scope
            return creationContext.toolSource().isFromTool(ToolType.REPEATER) && 
                   !capturedHeaders.isEmpty() &&
                   api.scope().isInScope(requestResponse.request().url());
        }
        
        @Override
        public String caption() {
            return "Auto-modified";
        }
        
        @Override
        public Component uiComponent() {
            return requestEditor.uiComponent();
        }
        
        @Override
        public Selection selectedData() {
            return requestEditor.selection().isPresent() ? requestEditor.selection().get() : null;
        }
        
        @Override
        public boolean isModified() {
            return requestEditor.isModified();
        }
    }
}