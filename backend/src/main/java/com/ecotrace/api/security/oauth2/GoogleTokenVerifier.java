package com.ecotrace.api.security.oauth2;

import com.ecotrace.api.config.properties.OAuth2Properties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.annotation.PostConstruct;
import java.net.URL;
import java.text.ParseException;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GoogleTokenVerifier {

    private final OAuth2Properties props;
    private ConfigurableJWTProcessor<SecurityContext> processor;

    public GoogleTokenVerifier(OAuth2Properties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws Exception {
        OAuth2Properties.Google g = props.google();
        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                .create(new URL(g.jwksUri()))
                .cache(g.jwksCacheTtl().toMillis(), 30_000)
                .retrying(true)
                .build();
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().issuer(g.issuer()).audience(g.clientId()).build(),
                Set.of("sub", "iss", "aud", "exp", "iat", "email", "email_verified")));
        this.processor = p;
    }

    public GoogleIdentity verify(String idToken) {
        try {
            JWTClaimsSet claims = processor.process(idToken, null);
            Object verified = claims.getClaim("email_verified");
            boolean emailVerified = verified instanceof Boolean b && b;
            if (!emailVerified) {
                throw new IllegalArgumentException("Google email not verified");
            }
            return new GoogleIdentity(
                    claims.getSubject(),
                    (String) claims.getClaim("email"),
                    (String) claims.getClaim("name"),
                    (String) claims.getClaim("picture"));
        } catch (ParseException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid Google ID token: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Google ID token verification failed", e);
        }
    }

    public record GoogleIdentity(String sub, String email, String name, String pictureUrl) {}
}
