package lab.common;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Validates the bearer token and checks that logon / CNPJ in the URL match the token (403 otherwise). */
@Component
public class TokenGuard {

    private final JwtService jwt;

    public TokenGuard(JwtService jwt) {
        this.jwt = jwt;
    }

    /** @param document CNPJ from the path, or {@code null} when the path has none (endpoints 1 and 4). */
    public Claims require(String authorization, String logon, String document) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        Claims claims;
        try {
            claims = jwt.verify(authorization.substring(7).trim());
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
        if (!claims.logon().equals(logon)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "logon in path does not match token");
        }
        if (document != null && !Ids.digits(document).equals(Ids.digits(claims.document()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "document in path does not match token");
        }
        return claims;
    }
}
