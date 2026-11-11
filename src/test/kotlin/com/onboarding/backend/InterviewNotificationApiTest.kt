package com.onboarding.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterviewNotificationApiTest : ApiTestSupport() {

    private fun request(from: Session, to: Session, at: String = future()) = post(
        "/api/v1/interviews", from.token, """{"target_user_id":"${to.userId}","preferred_at":"$at","reason":"적응 상담"}""",
    )

    private fun unread(s: Session): Int = get("/api/v1/notifications", s.token).read("$.data.unread_count")

    @Test
    fun `면담은 신청을 받은 본인만 승인하고 결과는 신청자에게 알린다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val newbie = newMember(hr, "NEWBIE", dept, "신입")
        val mentorB = newMember(hr, "MENTOR", dept, "멘토B")
        val mentorC = newMember(hr, "MENTOR", dept, "멘토C")

        val created = request(newbie, mentorB)
        assertEquals(201, created.status)
        val id: String = created.read("$.data.interview.id")
        assertEquals(1, unread(mentorB))
        assertEquals(0, unread(newbie))

        assertEquals(403, post("/api/v1/interviews/$id/approve", mentorC.token).status)
        assertEquals(403, post("/api/v1/interviews/$id/approve", newbie.token).status)

        val approved = post("/api/v1/interviews/$id/approve", mentorB.token)
        assertEquals(200, approved.status)
        assertEquals("APPROVED", approved.read("$.data.interview.status"))
        assertEquals(1, unread(newbie))
        assertEquals(409, post("/api/v1/interviews/$id/approve", mentorB.token).status)
        assertEquals(409, post("/api/v1/interviews/$id/reject", mentorB.token, """{"reason":"늦음"}""").status)
    }

    @Test
    fun `거절은 사유가 필수이고 같은 시간 중복 신청은 409`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val at = future(2)

        val id: String = request(mentor, hr, at).read("$.data.interview.id")
        assertEquals(409, request(mentor, hr, at).status)
        assertEquals(400, post("/api/v1/interviews", mentor.token, """{"target_user_id":"${hr.userId}","preferred_at":"2020-01-01T00:00:00Z","reason":"x"}""").status)

        assertEquals(400, post("/api/v1/interviews/$id/reject", hr.token, """{"reason":""}""").status)
        val rejected = post("/api/v1/interviews/$id/reject", hr.token, """{"reason":"일정이 맞지 않습니다"}""")
        assertEquals("REJECTED", rejected.read("$.data.interview.status"))
        assertEquals("일정이 맞지 않습니다", get("/api/v1/interviews/$id", mentor.token).read("$.data.interview.reject_reason"))
    }

    @Test
    fun `회사 전체 현황에서 당사자가 아니면 사유가 보이지 않고 신입은 열람할 수 없다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val newbie = newMember(hr, "NEWBIE", dept, "신입")
        val otherNewbie = newMember(hr, "NEWBIE", dept, "다른신입")
        val mentorB = newMember(hr, "MENTOR", dept, "멘토B")
        val mentorC = newMember(hr, "MENTOR", dept, "멘토C")
        val id: String = request(newbie, mentorB).read("$.data.interview.id")

        val byParty = get("/api/v1/interviews/$id", mentorB.token)
        assertEquals("적응 상담", byParty.read("$.data.interview.reason"))

        val byOtherMentor = get("/api/v1/interviews/$id", mentorC.token)
        assertEquals(200, byOtherMentor.status)
        assertNull(byOtherMentor.read<String?>("$.data.interview.reason"))
        assertEquals("신입", byOtherMentor.read("$.data.interview.requester.name"))

        assertEquals(1, get("/api/v1/interviews?direction=ALL", hr.token).read<List<Any>>("$.data.items").size)
        assertNull(get("/api/v1/interviews?direction=ALL", hr.token).read<String?>("$.data.items[0].reason"))
        assertEquals(0, get("/api/v1/interviews", mentorC.token).read<List<Any>>("$.data.items").size)
        assertEquals(1, get("/api/v1/interviews?direction=RECEIVED&status=PENDING", mentorB.token).read<List<Any>>("$.data.items").size)
        assertEquals(0, get("/api/v1/interviews?direction=SENT", mentorB.token).read<List<Any>>("$.data.items").size)

        assertEquals(403, get("/api/v1/interviews/$id", otherNewbie.token).status)
        assertEquals(403, get("/api/v1/interviews?direction=ALL", otherNewbie.token).status)
        assertEquals(404, get("/api/v1/interviews/$id", newCompany().token).status)
    }

    @Test
    fun `선택 알림은 끌 수 있고 필수 알림은 끌 수 없다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val path = "/api/v1/users/me/notification-preferences"

        val saved = put(path, hr.token, """{"interview":false,"company_schedule":false}""")
        assertEquals(200, saved.status)
        assertEquals(listOf(false), saved.read<List<Boolean>>("$.data.preferences[?(@.key=='interview')].enabled"))
        assertEquals(listOf(true), saved.read<List<Boolean>>("$.data.preferences[?(@.key=='company_schedule')].enabled"))
        assertEquals(listOf(true), get(path, hr.token).read<List<Boolean>>("$.data.preferences[?(@.key=='company_schedule')].mandatory"))
        assertEquals(400, put(path, hr.token, """{"unknown":false}""").status)

        assertEquals(201, request(mentor, hr).status)
        assertEquals(0, unread(hr))
    }

    @Test
    fun `알림은 본인 것만 읽음 처리할 수 있다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        request(mentor, hr, future(1))
        request(mentor, hr, future(2))

        val list = get("/api/v1/notifications?type=interview&unread_only=true", hr.token)
        assertEquals(2, list.read<Int>("$.data.unread_count"))
        val id: String = list.read("$.data.items[0].id")

        assertEquals(404, patch("/api/v1/notifications/$id/read", mentor.token).status)
        assertEquals(204, patch("/api/v1/notifications/$id/read", hr.token).status)
        assertEquals(1, unread(hr))
        assertEquals(204, patch("/api/v1/notifications/read-all", hr.token).status)
        assertEquals(0, unread(hr))
        assertEquals(2, get("/api/v1/notifications", hr.token).read<Int>("$.data.pagination.total_elements"))
    }
}
