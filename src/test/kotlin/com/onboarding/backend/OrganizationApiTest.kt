package com.onboarding.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OrganizationApiTest : ApiTestSupport() {

    @Test
    fun `부서명은 회사 안에서 중복될 수 없고 소속 직원이 있으면 삭제할 수 없다`() {
        val hr = newCompany()
        val dept = newDepartment(hr, "개발팀")
        assertEquals(409, post("/api/v1/departments", hr.token, """{"name":"개발팀"}""").status)

        newMember(hr, "NEWBIE", dept, "신입")
        assertEquals(409, delete("/api/v1/departments/$dept", hr.token).status)

        val empty = newDepartment(hr, "빈부서")
        assertEquals(200, patch("/api/v1/departments/$empty", hr.token, """{"name":"새부서"}""").status)
        assertEquals(204, delete("/api/v1/departments/$empty", hr.token).status)
        assertEquals(1, get("/api/v1/departments", hr.token).read<List<Any>>("$.data.items").size)
    }

    @Test
    fun `직원 관리 API는 HR 전용이라 멘토가 직접 호출하면 403`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val newbie = newMember(hr, "NEWBIE", dept, "신입")

        assertEquals(403, patch("/api/v1/employees/${newbie.userId}", mentor.token, """{"position":"대리"}""").status)
        assertEquals(403, post("/api/v1/invite-codes", mentor.token, """{"role":"NEWBIE","expires_at":"${future()}"}""").status)
        assertEquals(403, post("/api/v1/departments", mentor.token, """{"name":"x"}""").status)
        assertEquals(403, delete("/api/v1/employees/${newbie.userId}", mentor.token, """{"reason":"x"}""").status)
        assertEquals(
            403,
            put("/api/v1/employees/${newbie.userId}/mentor", mentor.token, """{"mentor_id":"${mentor.userId}","effective_at":"${future()}"}""").status,
        )
        assertEquals(200, get("/api/v1/employees", mentor.token).status)
    }

    @Test
    fun `직원 목록은 이름순이고 역할과 검색어로 거를 수 있으며 다른 회사 직원은 보이지 않는다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        newMember(hr, "NEWBIE", dept, "하신입")
        newMember(hr, "NEWBIE", dept, "가신입")
        newMember(hr, "MENTOR", dept, "나멘토")
        val other = newCompany()

        val newbies = get("/api/v1/employees?role=NEWBIE", hr.token)
        assertEquals(listOf("가신입", "하신입"), newbies.read<List<String>>("$.data.items[*].name"))
        assertEquals(1, get("/api/v1/employees?keyword=멘토", hr.token).read<List<Any>>("$.data.items").size)
        assertEquals(4, get("/api/v1/employees", hr.token).read<Int>("$.data.pagination.total_elements"))
        assertEquals(1, get("/api/v1/employees", other.token).read<Int>("$.data.pagination.total_elements"))
        assertEquals(404, patch("/api/v1/employees/${hr.userId}", other.token, """{"position":"x"}""").status)
    }

    @Test
    fun `신입-멘토 매칭은 역할을 검증하고 신입에게 내 멘토로 표시된다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val newbie = newMember(hr, "NEWBIE", dept, "신입")
        val newbie2 = newMember(hr, "NEWBIE", dept, "신입2")
        fun assign(newbieId: String, mentorId: String) =
            put("/api/v1/employees/$newbieId/mentor", hr.token, """{"mentor_id":"$mentorId","effective_at":"${future()}"}""")

        assertEquals(409, assign(newbie.userId, newbie2.userId).status)
        assertEquals(409, assign(mentor.userId, mentor.userId).status)
        val ok = assign(newbie.userId, mentor.userId)
        assertEquals(200, ok.status)
        assertEquals(mentor.userId, ok.read("$.data.assignment.mentor_id"))

        val seenByNewbie = get("/api/v1/employees?role=MENTOR", newbie.token)
        assertEquals(true, seenByNewbie.read("$.data.items[0].is_my_mentor"))
        val seenByMentor = get("/api/v1/employees?keyword=신입", mentor.token)
        assertEquals(listOf(true, false), seenByMentor.read<List<Boolean>>("$.data.items[*].is_my_mentee"))

        // 매칭이 남아 있으면 역할을 바꿀 수 없다
        assertEquals(409, patch("/api/v1/employees/${mentor.userId}", hr.token, """{"role":"NEWBIE"}""").status)
    }

    @Test
    fun `마지막 HR은 비활성화할 수 없고 비활성화된 직원의 토큰은 거부된다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val newbie = newMember(hr, "NEWBIE", dept, "신입")

        assertEquals(409, delete("/api/v1/employees/${hr.userId}", hr.token, """{"reason":"퇴사"}""").status)
        assertEquals(409, patch("/api/v1/employees/${hr.userId}", hr.token, """{"role":"MENTOR"}""").status)
        assertEquals(400, delete("/api/v1/employees/${newbie.userId}", hr.token, """{"reason":""}""").status)

        assertEquals(204, delete("/api/v1/employees/${newbie.userId}", hr.token, """{"reason":"퇴사"}""").status)
        assertEquals(401, get("/api/v1/users/me", newbie.token).status)
        assertEquals("INACTIVE", get("/api/v1/employees?role=NEWBIE", hr.token).read("$.data.items[0].status"))
    }

    @Test
    fun `HR이 미리 등록한 직원은 초대 코드 가입으로 계정을 활성화한다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val email = email()
        val body = """{"email":"$email","name":"예정자","role":"NEWBIE","department_id":"$dept","invite_mode":"INVITE"}"""

        val created = post("/api/v1/employees", hr.token, body)
        assertEquals(201, created.status)
        assertEquals("PENDING", created.read("$.data.employee.status"))
        assertEquals(409, post("/api/v1/employees", hr.token, body).status)
        assertEquals(401, post("/api/v1/auth/login", body = """{"email":"$email","password":"password1!"}""").status)

        assertEquals(201, signupInvite(newInviteCode(hr, "NEWBIE"), dept, "예정자", email).status)
        assertEquals(200, post("/api/v1/auth/login", body = """{"email":"$email","password":"password1!"}""").status)
        assertEquals(2, get("/api/v1/employees", hr.token).read<Int>("$.data.pagination.total_elements"))
    }
}
