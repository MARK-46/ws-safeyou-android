/*
 * Author: Mark Filatov (MARK-46)
 * LibName: WSClient
 * Contact: mark.38.98.ii@gmail.com, mark.9798@yandex.ru
 * Website: https://mark-46.github.io/resume
 * Date: Dec 20, 2023
 * License: MIT License (MIT)
 */

package space.safeyou.ws;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.LinkedList;
import java.util.Map;

@SuppressWarnings("unused")
public class WSClient {
    private static final String TAG = "WSClient";
    private final WSEvents events;
    private final WSOptions options;
    private final WebSocketClient webSocketClient;
    private final WSSenderQueueManager senderManager = new WSSenderQueueManager();
    private final WSPingManager pingManager = new WSPingManager();
    private String clientID = null;
    private String clientSID = "";
    private JSONObject clientInfo = null;
    private boolean isVerifiedConnection;
    private boolean isReconnecting = true;

    public WSClient(WSOptions options, WSEvents events) throws URISyntaxException {
        this.options = options;
        this.events = events;

        Map<String, String> httpHeaders = new HashMap<>();
        httpHeaders.put("sec-websocket-platform", "android");
        httpHeaders.put("Sec-Websocket-Protocol", this.options.protocol == null ? "" : this.options.protocol);
        httpHeaders.put("Cookie", String.format("X-Session-ID=%s", clientSID));

        webSocketClient = new WebSocketClient(new URI(this.options.url), new Draft_6455(), httpHeaders, this.options.connectTimeoutMs) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                clientID = "WS_PENDING";
                isVerifiedConnection = false;
                isReconnecting = true;
                Log.d(TAG, "(CONNECTING) / WebSocketClient ID: " + clientID);
                try {
                    events.onConnecting(WSClient.this);
                } catch (Exception ex) {
                    Log.e(TAG, "(CONNECTING) / Error handling connection event", ex);
                }
            }

            @Override
            public void onMessage(String s) {
                // No implementation needed
            }

            @Override
            public void onMessage(ByteBuffer buffer) {
                try {
                    byte[] bytes = buffer.array();
                    if (bytes.length <= 2) {
                        throw new Exception("Invalid data length received.");
                    }

                    WSPacket packet = WSUtils.readPacket(bytes);

                    if (packet.type < -1) {
                        throw new Exception("Invalid packet type received: " + packet.type);
                    }

                    if (packet.type == 0) {
                        handleConnectionVerification(packet);
                    } else {
                        handleReceivedPacket(packet);
                    }
                } catch (Exception ex) {
                    WSClient.this.onError(ex);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                reason = WSUtils.getReasonForCode(code, reason);
                clientID = null;
                isVerifiedConnection = false;
                Log.d(TAG, String.format("(DISCONNECTED) / CloseCode: \"%s\" -> CloseReason: %s", code, reason));
                try {
                    events.onDisconnected(WSClient.this, code, reason);
                } catch (Exception ex) {
                    Log.e(TAG, "(DISCONNECTED) / Error handling disconnection event", ex);
                }
                if (code == 3000 || code == 3003) { // stop reconnecting
                    return;
                }
                WSClient.this.reconnect();
            }

            @Override
            public void onError(Exception ex) {
                Log.e(TAG, "(ERROR) / Exception occurred: " + ex.getMessage(), ex);
                WSClient.this.onError(ex);
            }

            @Override
            public void onWebsocketPong(WebSocket conn, Framedata f) {
                super.onWebsocketPong(conn, f);
                pingManager.pong();
            }
        };

        senderManager.start();
        pingManager.start();
    }

    public synchronized String getClientID() {
        return clientID;
    }

    public synchronized String getClientSID() {
        return clientSID;
    }

    public synchronized JSONObject getClientInfo() {
        return clientInfo;
    }

    public synchronized boolean isConnected() {
        return webSocketClient.isOpen();
    }

    public void connect() {
        if (!webSocketClient.isOpen()) {
            if (isReconnecting) {
                Log.d(TAG, "(CONNECT) / URL: " + this.options.url);
                webSocketClient.connect();
            } else {
                isReconnecting = true;
                Log.d(TAG, "(RECONNECT) / URL: " + this.options.url);
                webSocketClient.reconnect();
            }
        }
    }

    public void disconnect(int code, String reason) {
        isReconnecting = false;
        Log.d(TAG, "(DISCONNECT) / Code: " + code + ", Reason: " + reason);
        if (webSocketClient.isOpen()) {
            reason = WSUtils.getReasonForCode(code, reason);
            webSocketClient.close(code, reason);
        }
    }

    private void reconnect() {
        if (!isReconnecting) {
            return;
        }
        new Thread(() -> {
            WSUtils.sleep(options.reconnectIntervalMs);
            if (!isReconnecting) {
                return;
            }
            if (webSocketClient.isClosed()) {
                webSocketClient.reconnect();
            }
        }, "WSClientReconnect").start();
    }

    public void sendPacket(JSONObject jsonData, byte[] fileContent) {
        try {
            String fileHash = WSUtils.sha256(fileContent);
            jsonData.put("file_hash", fileHash);

            byte[] metadataBytes = jsonData.toString().getBytes(StandardCharsets.UTF_8);
            byte[] bytes = new byte[metadataBytes.length + fileContent.length];

            System.arraycopy(metadataBytes, 0, bytes, 0, metadataBytes.length);
            System.arraycopy(fileContent, 0, bytes, metadataBytes.length, fileContent.length);

            senderManager.sendPacket(2, metadataBytes.length, bytes);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendPacket(JSONObject jsonData) {
        byte[] bytes = jsonData.toString().getBytes(StandardCharsets.UTF_8);
        senderManager.sendPacket(1, bytes.length, bytes);
    }

    private void handleConnectionVerification(WSPacket packet) {
        try {
            if (isVerifiedConnection) {
                throw new IllegalStateException("Already verified connection.");
            }

            JSONObject data = packet.getDataAsJSONObject();
            Log.d(TAG, "handleConnectionVerification: " + data);
            clientID = data.getString("id");
            clientSID = data.getString("sid");
            clientInfo = data.getJSONObject("info");

            webSocketClient.removeHeader("Cookie");
            webSocketClient.addHeader("Cookie", String.format("X-Session-ID=%s", clientSID));

            isVerifiedConnection = true;
            Log.d(TAG, "(CONNECTED) / WebSocketClient ID: " + clientID);
            try {
                events.onConnected(WSClient.this, clientID);
            } catch (Exception ex) {
                Log.e(TAG, "(CONNECTED) / Error handling connection event", ex);
            }
        } catch (Exception ex) {
            Log.e(TAG, "(CONNECTED) / Error handling connection verification", ex);
            onError(ex);
        }
    }

    private void handleReceivedPacket(WSPacket packet) {
        try {
            int type = packet.getType();
            byte[] data = packet.getDataAsBytes();

            String packetInfo = options.debugMode ?
                    String.format("(RECEIVED) / PacketType: \"%s\" -> PacketData: %s", type, new String(data)) :
                    String.format("(RECEIVED) / PacketType: \"%s\" -> PacketDataSize: %s", type, WSUtils.formatDataSize(data.length));
            Log.d(TAG, packetInfo);
            try {
                events.onReceivedPacket(WSClient.this, new WSPacket(type, data.length, data));
            } catch (Exception ex) {
                Log.e(TAG, "(RECEIVED) / Error handling received packet event", ex);
            }
        } catch (Exception ex) {
            Log.e(TAG, "(RECEIVED) / Error handling received packet", ex);
            onError(ex);
        }
    }

    private synchronized void onError(Exception ex) {
        Log.e(TAG, "(ERROR) / Exception occurred: " + ex.getMessage(), ex);
        try {
            events.onError(WSClient.this, ex);
        } catch (Exception ignored) {
        }
    }

    private class WSPingManager implements Runnable {
        private boolean isAlive = false;
        private int pongAttempts = 0;
        private long sendTime = 0;

        private void start() {
            Thread pingThread = new Thread(this, "WebSocketPingManager");
            pingThread.setDaemon(true);
            pingThread.start();
        }

        public synchronized void pong() {
            isAlive = true;
            pongAttempts = 0;
            long receivedTime = System.currentTimeMillis();
            try {
                WSClient.this.events.onPingTime(WSClient.this, receivedTime - sendTime);
            } catch (Exception ex) {
                Log.e(TAG, "(PING) / Error handling pong: " + ex.getMessage());
            }
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (webSocketClient.isOpen()) {
                        if (!isAlive) {
                            if (pongAttempts >= options.pingAttemptCount) {
                                WSClient.this.disconnect(3001, "Connection timeout");
                                return;
                            }
                            pongAttempts++;
                        }
                        sendTime = System.currentTimeMillis();
                        isAlive = false;
                        webSocketClient.sendPing();
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "(PING) / Error sending ping: " + ex.getMessage());
                }
                WSUtils.sleep(options.pingIntervalMs);
            }
        }
    }

    private class WSSenderQueueManager implements Runnable {
        private final Deque<WSPacket> packetQueue = new LinkedList<>();

        private void start() {
            Thread senderThread = new Thread(this, "WebSocketSenderQueueManager");
            senderThread.setDaemon(true);
            senderThread.start();
        }

        public synchronized void sendPacket(int type, int metadataLen, byte[] bytes) {
            if (type < 1 || type > 255) {
                Log.e(TAG, "(SEND) / The packet type must be between 1 and 255.");
                return;
            }
            packetQueue.offer(new WSPacket(type, metadataLen, bytes));
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (webSocketClient.isOpen()) {
                        WSPacket packet = packetQueue.poll();
                        if (packet != null) {
                            if (!sendWithRetry(packet)) {
                                packetQueue.offerFirst(packet);
                            }
                        }
                    }
                    WSUtils.sleep(100);
                } catch (Exception ex) {
                    Log.e(TAG, "(SEND) / Error: " + ex.getMessage());
                    WSUtils.sleep(500);
                }
            }
        }

        private boolean sendWithRetry(WSPacket packet) {
            try {
                logPacketInfo(packet);
                webSocketClient.send(packet.getPayload());
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "(SEND) / Error sending packet: " + ex.getMessage());
                return false;
            }
        }

        private void logPacketInfo(WSPacket packet) {
            String packetInfo = options.debugMode ?
                    String.format("(SEND) / PacketType: \"%s\" -> PacketData: %s", packet.getType(), packet.getDataAsString()) :
                    String.format("(SEND) / PacketType: \"%s\" -> PacketDataSize: %s", packet.getType(), WSUtils.formatDataSize(packet.getDataSize()));
            Log.d(TAG, packetInfo);
        }
    }

    public static class WSOptions {
        private String url;
        private String protocol;
        private boolean debugMode;
        private int connectTimeoutMs;
        private int reconnectIntervalMs;
        private int pingAttemptCount;
        private int pingIntervalMs;

        private WSOptions() {
            debugMode = false;
            reconnectIntervalMs = 5000;
            pingAttemptCount = 5;
            pingIntervalMs = 3000;
            connectTimeoutMs = 5000;
        }

        public WSOptions setUrl(String url) {
            this.url = url;
            return this;
        }

        public WSOptions setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public WSOptions setProtocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public WSOptions setDebugMode(boolean debugMode) {
            this.debugMode = debugMode;
            return this;
        }

        public WSOptions setReconnectIntervalMs(int reconnectIntervalMs) {
            this.reconnectIntervalMs = reconnectIntervalMs;
            return this;
        }

        public WSOptions setPingAttemptCount(int pingAttemptCount) {
            this.pingAttemptCount = pingAttemptCount;
            return this;
        }

        public WSOptions setPingIntervalMs(int pingIntervalMs) {
            this.pingIntervalMs = pingIntervalMs;
            return this;
        }

        public static WSOptions init() {
            return new WSOptions();
        }
    }

    private static class WSUtils {
        private static String sha256(final byte[] base) {
            StringBuilder sb = new StringBuilder();
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(base);
                for (byte x : digest.digest()) {
                    String str = Integer.toHexString(Byte.toUnsignedInt(x));
                    if (str.length() < 2) {
                        sb.append('0');
                    }
                    sb.append(str);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sha256: " + e.getMessage());
            }
            return sb.toString();
        }

        private static String getReasonForCode(int code, String reason) {
            if (reason != null && reason.length() > 2) {
                return reason;
            }
            if (code >= 0 && code <= 999) {
                return "(Unused)";
            } else if (code >= 1016 && code <= 4999) {
                if (code <= 1999) {
                    return "(For WebSocket standard)";
                } else if (code <= 2999) {
                    return "(For WebSocket extensions)";
                } else if (code <= 3999) {
                    return "(For libraries and frameworks)";
                } else {
                    return "(For applications)";
                }
            }
            switch (code) {
                case 1000:
                    return "Normal Closure";
                case 1001:
                    return "Going Away";
                case 1002:
                    return "Protocol Error";
                case 1003:
                    return "Unsupported Data";
                case 1004:
                    return "(For future)";
                case 1005:
                    return "No Status Received";
                case 1006:
                    return "Abnormal Closure";
                case 1007:
                    return "Invalid Frame Payload Data";
                case 1008:
                    return "Policy Violation";
                case 1009:
                    return "Message Too Big";
                case 1010:
                    return "Missing Extension";
                case 1011:
                    return "Internal Error";
                case 1012:
                    return "Service Restart";
                case 1013:
                    return "Try Again Later";
                case 1014:
                    return "Bad Gateway";
                case 1015:
                    return "TLS Handshake";
                default:
                    return reason;
            }
        }

        private static String formatDataSize(long bytes) {
            long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
            if (absB < 1024) {
                return bytes + " B";
            }
            long value = absB;
            CharacterIterator ci = new StringCharacterIterator("KMGTPE");
            for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
                value >>= 10;
                ci.next();
            }
            value *= Long.signum(bytes);
            return String.format(Locale.ENGLISH, "%.1f %ciB", value / 1024.0, ci.current());
        }

        private static void sleep(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
        }

        private static byte[] createPacket(int type, int metadataLen, byte[] data) {
            // Create an array to hold the data to be transmitted
            byte[] payload = new byte[data.length + 5]; // Packet size: data + 1 for the type and 4 for metadataLength

            // Place the type in the first byte of the packet
            payload[0] = (byte) type;

            // metadata length
            payload[1] = (byte) (metadataLen >> 24);
            payload[2] = (byte) (metadataLen >> 16);
            payload[3] = (byte) (metadataLen >> 8);
            payload[4] = (byte) metadataLen;

            // Copy data after the first byte into the packet
            System.arraycopy(data, 0, payload, 5, data.length);

            // Return the formed data packet
            return payload;
        }

        private static WSPacket readPacket(byte[] payload) {
            // Check if the byte array exists and contains data
            if (payload == null || payload.length <= 5) {
                // If there's no data to read, return null or throw an exception
                return null; // or throw new IllegalArgumentException("Invalid packet data");
            }

            // Get the data type from the 0 byte of the packet
            int packetType = payload[0];

            // Get the metadata length from the 1-4 bytes of the packet
            int metadataLen = ((payload[1] & 0xFF) << 24) |
                    ((payload[2] & 0xFF) << 16) |
                    ((payload[3] & 0xFF) << 8) |
                    (payload[4] & 0xFF);

            // Extract data from the remaining bytes of the packet
            byte[] data = new byte[payload.length - 5];
            System.arraycopy(payload, 5, data, 0, data.length);

            return new WSPacket(packetType, metadataLen, data);
        }
    }

    public static class WSPacket {
        private final int type;
        private final int metadataLen;
        private final byte[] data;
        private final byte[] fileData;

        private WSPacket(int type, int metadataLen, byte[] buffer) {
            this.type = type;
            this.metadataLen = metadataLen;
            data = new byte[metadataLen];
            System.arraycopy(buffer, 0, data, 0, metadataLen);

            if (buffer.length == metadataLen) {
                fileData = new byte[0];
            } else {
                fileData = new byte[buffer.length - metadataLen];
                System.arraycopy(buffer, metadataLen, fileData, 0, fileData.length);
            }
        }

        public int getType() {
            return type;
        }

        public int getDataSize() {
            return data.length;
        }

        public byte[] getDataAsBytes() {
            return data.clone();
        }

        public byte[] getFileDataAsBytes() {
            return fileData.clone();
        }

        public int getFileDataSize() {
            return fileData.length;
        }

        public String getDataAsString() {
            return new String(data, StandardCharsets.UTF_8);
        }

        public JSONObject getDataAsJSONObject() {
            try {
                return new JSONObject(getDataAsString());
            } catch (JSONException ex) {
                Log.e(TAG, "Error converting data to JSONObject: " + ex.getMessage());
                return null;
            }
        }

        public byte[] getPayload() {
            return WSUtils.createPacket(type, metadataLen, data);
        }
    }

    public interface WSEvents {
        void onConnecting(WSClient client) throws Exception;

        void onConnected(WSClient client, String id) throws Exception;

        void onDisconnected(WSClient client, int code, String reason) throws Exception;

        void onReceivedPacket(WSClient client, WSPacket packet) throws Exception;

        void onError(WSClient client, Exception exception) throws Exception;

        void onPingTime(WSClient client, long milliseconds) throws Exception;
    }
}