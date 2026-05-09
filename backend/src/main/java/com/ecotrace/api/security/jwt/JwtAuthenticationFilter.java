package com.ecotrace.api.security.jwt;

import com.ecotrace.api.security.principal.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final JwtBlocklistService blocklist;

    public JwtAuthenticationFilter(JwtService jwtService, JwtBlocklistService blocklist) {
        this.jwtService = jwtService;
        this.blocklist = blocklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtService.ParsedToken parsed = jwtService.verify(token);
                if (blocklist.isRevoked(parsed.jti())) {
                    log.debug("Rejected revoked token jti={}", parsed.jti());
                } else {
                    AuthenticatedUser user = parsed.user();
                    List<SimpleGrantedAuthority> authorities = user.roles().stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (IllegalArgumentException e) {
                log.debug("JWT rejected: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
