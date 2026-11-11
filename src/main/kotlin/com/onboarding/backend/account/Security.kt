package com.onboarding.backend.account

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.organization.User
import com.onboarding.backend.organization.UserRepository
import com.onboarding.backend.organization.UserStatus
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    var revokedAt: Instant? = null,
)

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?
    fun findAllByUserIdAndRevokedAtIsNull(userId: UUID): List<RefreshToken>
}

data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)

@Service
@Transactional
class TokenService(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.access-ttl-seconds}") val accessTtlSeconds: Long,
    @Value("\${app.jwt.refresh-ttl-days}") private val refreshTtlDays: Long,
    private val refreshTokens: RefreshTokenRepository,
) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256").also {
        require(secret.toByteArray().size >= 32) { "app.jwt.secret 은 32바이트 이상이어야 합니다." }
    }
    private val encoder = NimbusJwtEncoder(ImmutableSecret(key))
    private val random = SecureRandom()

    val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()

    fun accessToken(user: User): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(user.id.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(accessTtlSeconds))
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    fun issue(user: User): Tokens {
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        refreshTokens.save(
            RefreshToken(userId = user.id, tokenHash = hash(raw), expiresAt = Instant.now().plus(Duration.ofDays(refreshTtlDays))),
        )
        return Tokens(accessToken(user), raw, accessTtlSeconds)
    }

    /** 유효한 리프레시 토큰이면 소유자 ID를 돌려준다. */
    fun validRefreshOwner(raw: String): UUID? {
        val token = refreshTokens.findByTokenHash(hash(raw)) ?: return null
        if (token.revokedAt != null || !token.expiresAt.isAfter(Instant.now())) return null
        return token.userId
    }

    fun revoke(userId: UUID, raw: String?) {
        val now = Instant.now()
        if (raw == null) {
            refreshTokens.findAllByUserIdAndRevokedAtIsNull(userId).forEach { it.revokedAt = now }
        } else {
            refreshTokens.findByTokenHash(hash(raw))?.takeIf { it.userId == userId && it.revokedAt == null }?.revokedAt = now
        }
    }

    private fun hash(raw: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()))
}

@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    @Bean
    fun filterChain(http: HttpSecurity, tokens: TokenService, users: UserRepository): SecurityFilterChain {
        // 토큰의 주체를 매 요청마다 DB에서 다시 읽어 현재 역할·활성 상태를 반영한다.
        val converter = Converter<Jwt, AbstractAuthenticationToken> { jwt ->
            val user = runCatching { UUID.fromString(jwt.subject) }.getOrNull()?.let { users.findById(it).orElse(null) }
            if (user == null || user.status != UserStatus.ACTIVE) throw BadCredentialsException("사용할 수 없는 계정입니다.")
            UsernamePasswordAuthenticationToken(
                AuthUser(user.id, user.companyId, user.role), jwt, listOf(SimpleGrantedAuthority("ROLE_${user.role}")),
            )
        }
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/v1/auth/signup/**", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error",
                ).permitAll().anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs -> rs.jwt { it.decoder(tokens.decoder).jwtAuthenticationConverter(converter) } }
        return http.build()
    }
}
