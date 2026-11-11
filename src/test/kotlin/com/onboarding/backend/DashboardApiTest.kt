package com.onboarding.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DashboardApiTest : ApiTestSupport() {

    @Test
    fun `대시보드는 역할별로 접근이 나뉘고 멘토 홈은 내 담당과 회사 전체를 전환한다`() {
        val hr = newCompany()
        val dept = newDepartment(hr, "개발팀")
        val otherDept = newDepartment(hr, "영업팀")
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val mine = newMember(hr, "NEWBIE", dept, "담당신입")
        val other = newMember(hr, "NEWBIE", otherDept, "다른신입")
        put("/api/v1/employees/${mine.userId}/mentor", hr.token, """{"mentor_id":"${mentor.userId}","effective_at":"${future()}"}""")
        post("/api/v1/interviews", mine.token, """{"target_user_id":"${mentor.userId}","preferred_at":"${future(1)}","reason":"상담"}""")
        post("/api/v1/interviews", other.token, """{"target_user_id":"${hr.userId}","preferred_at":"${future(2)}","reason":"상담"}""")

        // DASH-001
        val hrDash = get("/api/v1/dashboard/hr", hr.token)
        assertEquals(200, hrDash.status)
        assertEquals(2, hrDash.read<List<Any>>("$.data.newbie_statuses").size)
        assertEquals(listOf("멘토"), hrDash.read<List<String>>("$.data.newbie_statuses[?(@.name=='담당신입')].mentor.name"))
        assertEquals(0, hrDash.read<Int>("$.data.summary.active_newbie_count"))
        assertEquals(1, get("/api/v1/dashboard/hr?department_id=$otherDept", mentor.token).read<List<Any>>("$.data.newbie_statuses").size)
        assertEquals(403, get("/api/v1/dashboard/hr", mine.token).status)

        // DASH-002
        val mineScope = get("/api/v1/dashboard/mentor", mentor.token)
        assertEquals(200, mineScope.status)
        assertEquals(listOf("담당신입"), mineScope.read<List<String>>("$.data.mentee_cards[*].name"))
        assertEquals(1, mineScope.read<Int>("$.data.action_counts.interview_requests"))
        val companyScope = get("/api/v1/dashboard/mentor?scope=COMPANY", mentor.token)
        assertEquals(2, companyScope.read<List<Any>>("$.data.mentee_cards").size)
        assertEquals(2, companyScope.read<Int>("$.data.action_counts.interview_requests"))
        assertEquals(403, get("/api/v1/dashboard/mentor", hr.token).status)

        // DASH-003: 온보딩 배정 데이터가 아직 연결되지 않아 배정 없음(404)
        assertEquals(404, get("/api/v1/dashboard/newbie", mine.token).status)
        assertEquals(403, get("/api/v1/dashboard/newbie", mentor.token).status)
    }
}
