package iped.engine.webapi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;

/**
 * Encodes and decodes a Lucene {@link ScoreDoc} (or {@link FieldDoc}) into an
 * opaque, URL-safe Base64 string so it can be passed back and forth as a
 * "cursor" parameter for efficient searchAfter-based pagination.
 * <p>
 * Wire format (big-endian):
 * <ul>
 *   <li><b>ScoreDoc</b>: {@code 'S' | doc (4B) | score (4B) | shardIndex (4B)}</li>
 *   <li><b>FieldDoc</b>: {@code 'F' | doc (4B) | score (4B) | shardIndex (4B) |
 *       N (2B) | field values…}</li>
 * </ul>
 * Each field value is prefixed by a single type marker byte:
 * {@code 'N'} null, {@code 'I'} int, {@code 'J'} long, {@code 'D'} double,
 * {@code 'F'} float, {@code 'S'} UTF-8 string (length-prefixed 4 bytes).
 */
public final class CursorCodec {

    private CursorCodec() { /* utility class */ }

    // -------- Encoding --------

    /**
     * Encode a ScoreDoc (or FieldDoc) into an opaque cursor token.
     *
     * @return Base64 URL-safe string, or {@code null} if {@code doc} is null
     */
    public static String encode(ScoreDoc doc) {
        if (doc == null) return null;

        if (doc instanceof FieldDoc) {
            return encodeFieldDoc((FieldDoc) doc);
        }

        // Plain ScoreDoc: marker + doc + score + shardIndex  →  13 bytes
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put((byte) 'S');
        buf.putInt(doc.doc);
        buf.putFloat(doc.score);
        buf.putInt(doc.shardIndex);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
    }

    private static String encodeFieldDoc(FieldDoc fd) {
        // First pass: compute required buffer size
        int size = 1 + 4 + 4 + 4 + 2; // marker + doc + score + shardIndex + fieldCount
        Object[] fields = fd.fields;
        if (fields != null) {
            for (Object f : fields) {
                size += fieldValueSize(f);
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put((byte) 'F');
        buf.putInt(fd.doc);
        buf.putFloat(fd.score);
        buf.putInt(fd.shardIndex);
        buf.putShort((short) (fields != null ? fields.length : 0));
        if (fields != null) {
            for (Object f : fields) {
                writeFieldValue(buf, f);
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
    }

    // -------- Decoding --------

    /**
     * Decode a cursor token back into a ScoreDoc or FieldDoc.
     *
     * @return ScoreDoc/FieldDoc, or {@code null} if token is null/empty
     * @throws IllegalArgumentException on malformed input
     */
    public static ScoreDoc decode(String token) {
        if (token == null || token.isEmpty()) return null;

        byte[] data;
        try {
            data = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor token", e);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte marker = buf.get();

        if (marker == 'S') {
            return new ScoreDoc(buf.getInt(), buf.getFloat(), buf.getInt());
        } else if (marker == 'F') {
            int doc = buf.getInt();
            float score = buf.getFloat();
            int shardIndex = buf.getInt();
            int nFields = buf.getShort() & 0xFFFF;
            Object[] fields = new Object[nFields];
            for (int i = 0; i < nFields; i++) {
                fields[i] = readFieldValue(buf);
            }
            FieldDoc fd = new FieldDoc(doc, score, fields, shardIndex);
            return fd;
        } else {
            throw new IllegalArgumentException("Unknown cursor marker: " + (char) marker);
        }
    }

    // -------- Field value helpers --------

    private static int fieldValueSize(Object value) {
        if (value == null) return 1;
        if (value instanceof Integer) return 1 + 4;
        if (value instanceof Long) return 1 + 8;
        if (value instanceof Float) return 1 + 4;
        if (value instanceof Double) return 1 + 8;
        if (value instanceof String) {
            byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
            return 1 + 4 + utf8.length;
        }
        throw new IllegalArgumentException("Unsupported sort field type: " + value.getClass().getName());
    }

    private static void writeFieldValue(ByteBuffer buf, Object value) {
        if (value == null) {
            buf.put((byte) 'N');
        } else if (value instanceof Integer) {
            buf.put((byte) 'I');
            buf.putInt((Integer) value);
        } else if (value instanceof Long) {
            buf.put((byte) 'J');
            buf.putLong((Long) value);
        } else if (value instanceof Float) {
            buf.put((byte) 'F');
            buf.putFloat((Float) value);
        } else if (value instanceof Double) {
            buf.put((byte) 'D');
            buf.putDouble((Double) value);
        } else if (value instanceof String) {
            byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
            buf.put((byte) 'S');
            buf.putInt(utf8.length);
            buf.put(utf8);
        } else {
            throw new IllegalArgumentException("Unsupported sort field type: " + value.getClass().getName());
        }
    }

    private static Object readFieldValue(ByteBuffer buf) {
        byte type = buf.get();
        switch (type) {
            case 'N': return null;
            case 'I': return buf.getInt();
            case 'J': return buf.getLong();
            case 'F': return buf.getFloat();
            case 'D': return buf.getDouble();
            case 'S': {
                int len = buf.getInt();
                byte[] utf8 = new byte[len];
                buf.get(utf8);
                return new String(utf8, StandardCharsets.UTF_8);
            }
            default:
                throw new IllegalArgumentException("Unknown field type marker: " + (char) type);
        }
    }
}
