package dev.concord.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Password hashing with argon2id (SPEC §9.4), OWASP's minimum parameters: 19 MiB, 2 passes, one
 * lane. Stored in the PHC string format, so the parameters travel with each hash and can be
 * raised later without breaking old hashes. BouncyCastle is pure Java: no native library.
 */
public final class Passwords {
    private Passwords() {}

    static final int MEMORY_KIB = 19 * 1024, PASSES = 2, LANES = 1, SALT = 16, LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();

    public static String hash(String password) {
        byte[] salt = new byte[SALT];
        RANDOM.nextBytes(salt);
        byte[] out = derive(password, salt, MEMORY_KIB, PASSES, LANES, LENGTH);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + PASSES + ",p=" + LANES + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(out);
    }

    /** False for a wrong password or a hash this code did not write. Constant time in the hash. */
    public static boolean verify(String password, String phc) {
        try {
            String[] p = phc.split("\\$");
            if (p.length != 6 || !p[1].equals("argon2id") || !p[2].equals("v=19")) return false;
            int m = 0, t = 0, l = 0;
            for (String kv : p[3].split(",")) {
                int v = Integer.parseInt(kv.substring(2));
                switch (kv.charAt(0)) {
                    case 'm' -> m = v;
                    case 't' -> t = v;
                    case 'p' -> l = v;
                    default -> { return false; }
                }
            }
            byte[] salt = Base64.getDecoder().decode(p[4]), expected = Base64.getDecoder().decode(p[5]);
            return MessageDigest.isEqual(expected, derive(password, salt, m, t, l, expected.length));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int memoryKib, int passes, int lanes, int length) {
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(memoryKib).withIterations(passes).withParallelism(lanes).build());
        byte[] out = new byte[length];
        g.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
