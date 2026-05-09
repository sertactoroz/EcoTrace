package com.ecotrace.api.security.jwt;

import com.ecotrace.api.config.properties.JwtProperties;
import com.ecotrace.api.security.principal.AuthenticatedUser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties props;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String keyId;

    public JwtService(JwtProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        try {
            JwtProperties.Signing signing = props.signing();
            if (signing != null && signing.privateKey() != null && !signing.privateKey().isBlank()
                    && signing.publicKey() != null && !signing.publicKey().isBlank()) {
                this.privateKey = parsePrivate(signing.privateKey());
                this.publicKey = parsePublic(signing.publicKey());
                this.keyId = signing.keyId() != null ? signing.keyId() : "configured";
            } else {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();
                this.privateKey = (RSAPrivateKey) kp.getPrivate();
                this.publicKey = (RSAPublicKey) kp.getPublic();
                this.keyId = "ephemeral-" + UUID.randomUUID();
                log.warn("JWT signing key not configured — generated ephemeral keypair (kid={}). "
                        + "DO NOT use in production.", keyId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JWT signing keys", e);
        }
    }

    public IssuedToken issueAccessToken(AuthenticatedUser user) {
        return issue(user, props.accessTokenTtl(), "access");
    }

    public IssuedToken issueRefreshToken(AuthenticatedUser user) {
        return issue(user, props.refreshTokenTtl(), "refresh");
    }

    private IssuedToken issue(AuthenticatedUser user, Duration ttl, String typ) {
        try {
            String jti = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Instant exp = now.plus(ttl);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.userId().toString())
                    .issuer(props.issuer())
                    .audience(props.audience())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(jti)
                    .claim("typ", typ)
                    .claim("email", user.email())
                    .claim("roles", List.copyOf(user.roles()))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                    claims);
            jwt.sign(new RSASSASigner(privateKey));
            return new IssuedToken(jwt.serialize(), jti, exp);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    public ParsedToken verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                throw new IllegalArgumentException("Invalid signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                throw new IllegalArgumentException("Token expired");
            }
            if (!props.issuer().equals(claims.getIssuer())) {
                throw new IllegalArgumentException("Invalid issuer");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(props.audience())) {
                throw new IllegalArgumentException("Invalid audience");
            }
            UUID userId = UUID.fromString(claims.getSubject());
            String email = (String) claims.getClaim("email");
            Object rolesRaw = claims.getClaim("roles");
            Set<String> roles = rolesRaw instanceof List<?> list
                    ? list.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet())
                    : Set.of();
            return new ParsedToken(
                    new AuthenticatedUser(userId, email, roles),
                    claims.getJWTID(),
                    exp.toInstant());
        } catch (ParseException | JOSEException e) {
            throw new IllegalArgumentException("Token parse/verify failed: " + e.getMessage(), e);
        }
    }

    public RSAKey jwk() {
        return new RSAKey.Builder(publicKey).keyID(keyId).build();
    }

    private static RSAPrivateKey parsePrivate(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPem(pem));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey parsePublic(String pem) throws Exception {
        byte[] der = Base64.getDecoder().decode(stripPem(pem));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
    }

    public record IssuedToken(String token, String jti, Instant expiresAt) {}

    public record ParsedToken(AuthenticatedUser user, String jti, Instant expiresAt) {
        public Base64URL jtiUrl() { return Base64URL.encode(jti); }
    }
}
