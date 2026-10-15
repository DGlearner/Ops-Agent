package io.github.dglearner.opsagent.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** UUID helpers. */
public final class Ids {

    /** Namespace for {@link #nameBased}; fixed so ids stay stable across releases. */
    private static final UUID NAMESPACE = UUID.fromString("4d1f6c2e-8b1a-4f5e-9c3d-6a7b8c9d0e1f");

    /**
     * A version 7 UUID (RFC 9562): 48-bit Unix milliseconds followed by random bits. Ids created later sort later,
     * which keeps B-tree inserts sequential and lets the id double as a pagination cursor.
     */
    public static UUID newId() {
        long millis = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (millis << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** A version 5 UUID: the same name always gives the same id. Used for idempotent alert ids. */
    public static UUID nameBased(String name) {
        byte[] hash;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(NAMESPACE));
            hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every Java platform", e);
        }
        hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xFF);
            lsb = (lsb << 8) | (hash[i + 8] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (56 - 8 * i));
            bytes[i + 8] = (byte) (lsb >>> (56 - 8 * i));
        }
        return bytes;
    }

    private Ids() {
    }
}
