package ca.rewr.sable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the last N messages per room for multi-message notifications.
 */
public class MessageHistory {

    private static final int MAX_MESSAGES = 3;

    public static class Message {
        public final String senderName;
        public final String body;
        public final long timestamp;
        public android.graphics.Bitmap avatar;

        public Message(String senderName, String body, long timestamp, android.graphics.Bitmap avatar) {
            this.senderName = senderName;
            this.body = body;
            this.timestamp = timestamp;
            this.avatar = avatar;
        }
    }

    private final Map<String, Deque<Message>> history = new HashMap<>();

    public void add(String roomId, String senderName, String body, android.graphics.Bitmap avatar) {
        Deque<Message> queue = history.get(roomId);
        if (queue == null) {
            queue = new ArrayDeque<>();
            history.put(roomId, queue);
        }
        queue.addLast(new Message(senderName, body, System.currentTimeMillis(), avatar));
        while (queue.size() > MAX_MESSAGES) queue.pollFirst();
    }

    public List<Message> get(String roomId) {
        Deque<Message> queue = history.get(roomId);
        return queue != null ? new ArrayList<>(queue) : new ArrayList<>();
    }

    public void clear(String roomId) {
        history.remove(roomId);
    }
}
