package lab.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HS256 JWT issuer/verifier. Lab-grade on purpose (shared secret, no key rotation).
 * The token carries {@code sub} = logon and a configurable claim (default {@code cnpj}) = company document.
 */
public class JwtService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final String cnpjClaim;

    public JwtService(String secret, String cnpjClaim) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("lab.jwt.secret must have at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.cnpjClaim = cnpjClaim;
    }

    public String issue(String logon, String cnpj, Duration ttl) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", logon);
        payload.put(cnpjClaim, cnpj);
        payload.put("iat", now);
        payload.put("exp", now + ttl.toSeconds());
        String signingInput = b64(MAPPER.writeValueAsString(header)) + "." + b64(MAPPER.writeValueAsString(payload));
        return signingInput + "." + B64E.encodeToString(hmac(signingInput));
    }

    @SuppressWarnings("unchecked")
    public Claims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new JwtException("malformed token");
            }
            Map<String, Object> header = MAPPER.readValue(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8), Map.class);
            if (!"HS256".equals(header.get("alg"))) {
                throw new JwtException("unsupported alg");
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            byte[] given = B64D.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, given)) {
                throw new JwtException("bad signature");
            }
            Map<String, Object> payload = MAPPER.readValue(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8), Map.class);
            Object exp = payload.get("exp");
            if (!(exp instanceof Number n) || n.longValue() < Instant.now().getEpochSecond()) {
                throw new JwtException("token expired");
            }
            Object sub = payload.get("sub");
            Object cnpj = payload.get(cnpjClaim);
            if (sub == null || cnpj == null) {
                throw new JwtException("missing sub or " + cnpjClaim + " claim");
            }
            return new Claims(String.valueOf(sub), String.valueOf(cnpj));
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("invalid token");
        }
    }

    private static String b64(String json) {
        return B64E.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
