package com.onboarding.backend.notification

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.NotificationCategory
import com.onboarding.backend.common.NotifyEvent
import com.onboarding.backend.common.Pagination
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.notFound
import com.onboarding.backend.common.pageOf
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notifications")
class Notification(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val recipientId: UUID,
    @Enumerated(EnumType.STRING) val category: NotificationCategory,
    val mandatory: Boolean,
    val title: String,
    val body: String? = null,
    val linkType: String? = null,
    val linkId: UUID? = null,
    var readAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "notification_preferences")
class NotificationPreference(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    @Enumerated(EnumType.STRING) val category: NotificationCategory,
    var enabled: Boolean,
)

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findAllByRecipientId(recipientId: UUID, pageable: Pageable): Page<Notification>
    fun findAllByRecipientIdAndCategory(recipientId: UUID, category: NotificationCategory, pageable: Pageable): Page<Notification>
    fun findAllByRecipientIdAndReadAtIsNull(recipientId: UUID, pageable: Pageable): Page<Notification>
    fun findAllByRecipientIdAndCategoryAndReadAtIsNull(
        recipientId: UUID, category: NotificationCategory, pageable: Pageable,
    ): Page<Notification>
    fun countByRecipientIdAndReadAtIsNull(recipientId: UUID): Long
    fun findByIdAndRecipientId(id: UUID, recipientId: UUID): Notification?

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientId = :recipientId and n.readAt is null")
    fun markAllRead(recipientId: UUID, now: Instant): Int
}

interface NotificationPreferenceRepository : JpaRepository<NotificationPreference, UUID> {
    fun findAllByUserId(userId: UUID): List<NotificationPreference>
    fun findAllByCategoryAndEnabledFalseAndUserIdIn(category: NotificationCategory, userIds: Collection<UUID>): List<NotificationPreference>
}

/** NotifyEvent를 받아 수신자별 알림을 저장한다. 발행한 쪽 트랜잭션 안에서 함께 처리된다. */
@Component
class NotificationListener(
    private val notifications: NotificationRepository,
    private val preferences: NotificationPreferenceRepository,
) {
    @EventListener
    fun on(event: NotifyEvent) {
        var recipients = event.recipientIds.toSet() - setOfNotNull(event.actorId)
        if (recipients.isEmpty()) return
        if (!event.mandatory && event.category.optional) {
            val optedOut = preferences.findAllByCategoryAndEnabledFalseAndUserIdIn(event.category, recipients).map { it.userId }.toSet()
            recipients = recipients - optedOut
        }
        notifications.saveAll(
            recipients.map {
                Notification(
                    companyId = event.companyId, recipientId = it, category = event.category, mandatory = event.mandatory,
                    title = event.title, body = event.body, linkType = event.linkType, linkId = event.linkId,
                )
            },
        )
    }
}

data class NotificationDto(
    val id: UUID,
    val type: String,
    val mandatory: Boolean,
    val title: String,
    val body: String?,
    val linkType: String?,
    val linkId: UUID?,
    val read: Boolean,
    val createdAt: Instant,
)
data class NotificationListResponse(val items: List<NotificationDto>, val unreadCount: Long, val pagination: Pagination)

@RestController
@RequestMapping("/api/v1/notifications")
@Transactional
class NotificationController(private val notifications: NotificationRepository) {

    /** NOTI-001 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam(required = false) type: String?,
        @RequestParam("unread_only", defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<NotificationListResponse> {
        val category = type?.let { t -> NotificationCategory.entries.find { it.key == t.lowercase() } ?: throw badRequest("알 수 없는 알림 종류입니다.") }
        val pageable = pageOf(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = when {
            category != null && unreadOnly -> notifications.findAllByRecipientIdAndCategoryAndReadAtIsNull(me.id, category, pageable)
            category != null -> notifications.findAllByRecipientIdAndCategory(me.id, category, pageable)
            unreadOnly -> notifications.findAllByRecipientIdAndReadAtIsNull(me.id, pageable)
            else -> notifications.findAllByRecipientId(me.id, pageable)
        }
        return ApiResponse(
            NotificationListResponse(
                result.content.map {
                    NotificationDto(it.id, it.category.key, it.mandatory, it.title, it.body, it.linkType, it.linkId, it.readAt != null, it.createdAt)
                },
                notifications.countByRecipientIdAndReadAtIsNull(me.id),
                Pagination(result.number + 1, result.size, result.totalElements, result.totalPages),
            ),
        )
    }

    /** NOTI-003 */
    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun readAll(@AuthenticationPrincipal me: AuthUser) {
        notifications.markAllRead(me.id, Instant.now())
    }

    /** NOTI-002 */
    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@AuthenticationPrincipal me: AuthUser, @PathVariable notificationId: UUID) {
        val n = notifications.findByIdAndRecipientId(notificationId, me.id) ?: throw notFound()
        if (n.readAt == null) n.readAt = Instant.now()
    }
}

data class PreferenceDto(val key: String, val enabled: Boolean, val mandatory: Boolean)
data class PreferencesResponse(val preferences: List<PreferenceDto>)

@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@Transactional
class NotificationPreferenceController(private val preferences: NotificationPreferenceRepository) {

    /** USER-004 */
    @GetMapping
    fun get(@AuthenticationPrincipal me: AuthUser) = ApiResponse(view(me.id))

    /** USER-005: {"task": false, "quiz": true} 형태. 필수 알림 종류는 끌 수 없어 항상 켜진 채로 남는다. */
    @PutMapping
    fun put(@AuthenticationPrincipal me: AuthUser, @RequestBody req: Map<String, Boolean>): ApiResponse<PreferencesResponse> {
        val stored = preferences.findAllByUserId(me.id).associateBy { it.category }
        req.forEach { (key, enabled) ->
            val category = NotificationCategory.entries.find { it.key == key } ?: throw badRequest("알 수 없는 알림 종류입니다: $key")
            if (!category.optional) return@forEach
            stored[category]?.let { it.enabled = enabled }
                ?: preferences.save(NotificationPreference(userId = me.id, category = category, enabled = enabled))
        }
        preferences.flush()
        return ApiResponse(view(me.id))
    }

    private fun view(userId: UUID): PreferencesResponse {
        val stored = preferences.findAllByUserId(userId).associate { it.category to it.enabled }
        return PreferencesResponse(
            NotificationCategory.entries.map { PreferenceDto(it.key, if (it.optional) stored[it] ?: true else true, !it.optional) },
        )
    }
}
