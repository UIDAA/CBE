package com.onboarding.backend.common

import java.util.UUID

/**
 * 알림 종류. optional=true 인 항목만 마이페이지에서 끌 수 있다(USER-005).
 * 나머지는 필수 알림 전용 종류다.
 */
enum class NotificationCategory(val optional: Boolean) {
    TASK(true), JOURNAL(true), QUIZ(true), INTERVIEW(true), MANUAL(true), DOCUMENT(true),
    CONTENT_CHANGED(false), COMPANY_SETTINGS(false), COMPANY_SCHEDULE(false), MANUAL_PERMISSION(false);

    val key: String get() = name.lowercase()
}

/**
 * 알림 생성 요청 이벤트. 어느 모듈이든 ApplicationEventPublisher로 발행하면 알림 모듈이 저장한다.
 * - 행위자(actorId)는 수신자에서 자동 제외된다.
 * - mandatory=false 이면 수신자의 알림 설정(category)을 따른다.
 */
data class NotifyEvent(
    val companyId: UUID,
    val actorId: UUID?,
    val recipientIds: Collection<UUID>,
    val category: NotificationCategory,
    val mandatory: Boolean,
    val title: String,
    val body: String? = null,
    val linkType: String? = null,
    val linkId: UUID? = null,
)
