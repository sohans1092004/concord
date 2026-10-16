package dev.concord.common;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import dev.concord.crdt.Hlc;
import java.time.Instant;

/** JWTs (SPEC §9.4): HS256, 15 minutes, scoped to one user (`sub` = user ID). */
public final class Tokens {
    public static final long TTL_SECONDS = 15 * 60;
    private final Algorithm alg;
    private final JWTVerifier verifier;

    public Tokens(String secret) {
        this.alg = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(alg).withIssuer("concord").build();
    }

    /** The user ID the token is for, or null if it is invalid, expired or not for a user. */
    public String verify(String token) {
        if (token == null) return null;
        try {
            String sub = verifier.verify(token).getSubject();
            return Hlc.isUuid(sub) ? sub : null;
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    public String mint(String userId, Instant now) {
        return JWT.create().withIssuer("concord").withSubject(userId)
                .withIssuedAt(now).withExpiresAt(now.plusSeconds(TTL_SECONDS)).sign(alg);
    }
}
