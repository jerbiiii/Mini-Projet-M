package common;

import java.io.Serializable;
import java.util.Date;

/**
 * Classe représentant un message échangé entre les composants via Sockets
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    // Types de messages
    public static final String TYPE_REGISTER = "REGISTER";
    public static final String TYPE_FAILURE = "FAILURE";
    public static final String TYPE_STORAGE_ALERT = "STORAGE_ALERT";
    public static final String TYPE_COMMAND = "COMMAND";
    public static final String TYPE_STATUS = "STATUS";
    public static final String TYPE_QUALITY_ISSUE = "QUALITY_ISSUE";
    public static final String TYPE_MAINTENANCE = "MAINTENANCE";
    public static final String TYPE_RESPONSE = "RESPONSE";

    // Commandes possibles
    public static final String CMD_START = "START";
    public static final String CMD_STOP = "STOP";
    public static final String CMD_REPLACE = "REPLACE";
    public static final String CMD_MAINTAIN = "MAINTAIN";

    private String messageType;
    private String senderId;
    private String content;
    private Date timestamp;

    public Message(String messageType, String senderId, String content) {
        this.messageType = messageType;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = new Date();
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public String serialize() {
        return messageType + "|" + senderId + "|" + content + "|" + timestamp.getTime();
    }

    public static Message deserialize(String data) {
        String[] parts = data.split("\\|");
        if (parts.length >= 3) {
            return new Message(parts[0], parts[1], parts[2]);
        }
        return null;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type='" + messageType + '\'' +
                ", sender='" + senderId + '\'' +
                ", content='" + content + '\'' +
                ", time=" + timestamp +
                '}';
    }
}