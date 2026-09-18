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
    private final long tokenExpirationMs;

    public JwtService(@Value("${jwt.secret:super_secret_jwt_key_at_least_32_characters_long}") String secret,
                      @Value("${jwt.expiration.ms:3600000}") long tokenExpirationMs) {
        if (secret == null || secret.isBlank()) {
            // Fallback for environment where JWT_SECRET is not explicitly provided during startup testing
            secret = "super_secret_jwt_key_at_least_32_characters_long";
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenExpirationMs = tokenExpirationMs;
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

    public String generateToken(Long accountId) {
        if (accountId == null || accountId < 1) {
            throw new IllegalArgumentException("accountId must be positive");
        }

        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + tokenExpirationMs);

        return Jwts.builder()
                .claim("accountId", accountId)
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }
}
