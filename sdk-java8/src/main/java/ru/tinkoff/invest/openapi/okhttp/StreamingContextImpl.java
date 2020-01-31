package ru.tinkoff.invest.openapi.okhttp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.tinkoff.invest.openapi.StreamingContext;
import ru.tinkoff.invest.openapi.model.streaming.StreamingEvent;
import ru.tinkoff.invest.openapi.model.streaming.StreamingRequest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

class StreamingContextImpl implements StreamingContext {

    private static final TypeReference<StreamingEvent> streamingEventTypeReference = new TypeReference<StreamingEvent>() {};

    private final StreamingApiListener streamingCallback;
    private final WebSocket[] wsClients;
    private final Map<WebSocket, Set<StreamingRequest.ActivatingRequest>> requestsHistory;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final OkHttpClient client;
    private final Request wsRequest;

    StreamingContextImpl(final OkHttpClient client,
                         final String streamingUrl,
                         final String authToken,
                         final int streamingParallelism,
                         final Consumer<StreamingEvent> streamingEventCallback,
                         final Consumer<Throwable> streamingErrorCallback,
                         final Logger logger) {
        this.logger = logger;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        this.streamingCallback = new StreamingContextImpl.StreamingApiListener(streamingEventCallback, streamingErrorCallback, mapper, logger);
        this.wsClients = new WebSocket[streamingParallelism];
        this.requestsHistory = new HashMap<>(streamingParallelism);
        this.wsRequest = new Request.Builder()
                .url(streamingUrl)
                .header("Authorization", authToken)
                .build();
        for (int i = 0; i < streamingParallelism; i++) {
            this.wsClients[i] = this.client.newWebSocket(this.wsRequest, this.streamingCallback);
            this.requestsHistory.put(this.wsClients[i], new HashSet<>());
        }
    }

    @Override
    public void sendRequest(StreamingRequest request) {
        int clientIndex = request.hashCode() % this.wsClients.length;

        try {
            final String message = mapper.writeValueAsString(request);
            final WebSocket wsClient = this.wsClients[clientIndex];

            final Set<StreamingRequest.ActivatingRequest> wsClientHistory = this.requestsHistory.get(wsClient);
            wsClientHistory.removeIf(hr -> hr.onOffPairId().equals(request.onOffPairId()));
            if (request instanceof StreamingRequest.ActivatingRequest) {
                wsClientHistory.add((StreamingRequest.ActivatingRequest) request);
            }

            wsClient.send(message);
        } catch (JsonProcessingException ex) {
            logger.log(Level.SEVERE, "Что-то произошло при посыле сообщения в Streaming API", ex);
            streamingCallback.handleError(ex);
        }
    }

    @Override
    public void close() {
        for (final WebSocket ws : this.wsClients) {
            ws.close(1000, null);
        }
    }

    public void restore(final WebSocket webSocket) throws Exception {
        for (int i = 0; i < this.wsClients.length; i++) {
            final WebSocket wsClient = this.wsClients[i];
            if (wsClient == webSocket) {
                final WebSocket newWsClient = this.client.newWebSocket(this.wsRequest, this.streamingCallback);
                this.wsClients[i] = newWsClient;
                final Set<StreamingRequest.ActivatingRequest> history = this.requestsHistory.remove(wsClient);
                this.requestsHistory.put(newWsClient, history);

                for (final StreamingRequest.ActivatingRequest request : history) {
                    final String message = mapper.writeValueAsString(request);
                    wsClient.send(message);
                }

                break;
            }
        }
    }

    class StreamingApiListener extends WebSocketListener implements StreamingEventHandler {

        private final Consumer<StreamingEvent> streamingEventCallback;
        private final Consumer<Throwable> streamingErrorCallback;
        private final ObjectMapper mapper;
        private final Logger logger;

        StreamingApiListener(final Consumer<StreamingEvent> streamingEventCallback,
                             final Consumer<Throwable> streamingErrorCallback,
                             final ObjectMapper mapper,
                             final Logger logger) {
            this.streamingEventCallback = streamingEventCallback;
            this.streamingErrorCallback = streamingErrorCallback;
            this.mapper = mapper;
            this.logger = logger;
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            super.onMessage(webSocket, text);

            try {
                final StreamingEvent event = this.mapper.readValue(text, streamingEventTypeReference);
                this.handleEvent(event);
            } catch (JsonProcessingException ex) {
                logger.log(Level.SEVERE, "Что-то произошло при обработке события в Streaming API", ex);
                this.handleError(ex);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
            super.onFailure(webSocket, t, response);

            logger.log(Level.SEVERE, "Что-то произошло в Streaming API", t);

            try {
                restore(webSocket);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "При закрытии Streaming API что-то произошло", ex);
                this.streamingErrorCallback.accept(ex);
            }
        }

        @Override
        public void handleEvent(final StreamingEvent event) {
            this.streamingEventCallback.accept(event);
        }

        @Override
        public void handleError(final Throwable error) {
            this.streamingErrorCallback.accept(error);
        }
    }

}
