package com.trading.tradeapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey secretKey;

    public JwtService(@Value("${jwt.secret:default-secret-key-that-is-at-least-32-bytes-long}") String secret) {
        if (secret == null || secret.isBlank()) {
            // Fallback for environment where JWT_SECRET is not explicitly provided during startup testing
            secret = "default-secret-key-that-is-at-least-32-bytes-long";
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims validateAndParseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Token is missing");
        }

        // Verify header algorithm and signature
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);

        // Verify algorithm strictly (e.g. HS256)
        String alg = jws.getHeader().getAlgorithm();
        if (!"HS256".equalsIgnoreCase(alg)) {
            throw new JwtException("Unsupported algorithm: " + alg);
        }

        // Check expiration
        Claims claims = jws.getPayload();
        if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
            throw new JwtException("Token expired");
        }

        return claims;
    }

    public Long extractAccountId(Claims claims) {
        Object accountIdObj = claims.get("accountId");
        if (accountIdObj == null) {
            return null;
        }
        if (accountIdObj instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(accountIdObj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
