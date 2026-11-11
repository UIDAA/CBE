package com.onboarding.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AccountApiTest : ApiTestSupport() {

    @Test
    fun `회사 가입 후 HR로 로그인하고 내 정보를 조회한다`() {
        val email = email()
        val hr = newCompany(email)

        val login = post("/api/v1/auth/login", body = """{"email":"$email","password":"password1!"}""")
        assertEquals(200, login.status)
        assertEquals("HR", login.read("$.data.role"))

        val me = get("/api/v1/users/me", hr.token)
        assertEquals(200, me.status)
        assertEquals(email, me.read("$.data.profile.email"))
        assertEquals("테스트회사", me.read("$.data.workspace.name"))
    }

    @Test
    fun `회사 가입은 중복 이메일 409, 비밀번호 규칙 위반과 확인 불일치 400`() {
        val email = email()
        newCompany(email)
        fun signup(e: String, pw: String, confirm: String) = post(
            "/api/v1/auth/signup/workspace",
            body = """{"company_name":"회사","plan_type":"PRO","email":"$e","password":"$pw","password_confirm":"$confirm","name":"이름"}""",
        ).status
        assertEquals(409, signup(email, "password1!", "password1!"))
        assertEquals(400, signup(email(), "short", "short"))
        assertEquals(400, signup(email(), "password1!", "different1!"))
    }

    @Test
    fun `로그인 실패는 401이고 인증 없이 보호 API를 호출하면 401`() {
        val email = email()
        newCompany(email)
        assertEquals(401, post("/api/v1/auth/login", body = """{"email":"$email","password":"wrong-password"}""").status)
        assertEquals(401, get("/api/v1/users/me").status)
        assertEquals(401, get("/api/v1/users/me", "not-a-token").status)
    }

    @Test
    fun `리프레시 토큰으로 액세스 토큰을 재발급하고 로그아웃하면 폐기된다`() {
        val hr = newCompany()
        val refreshed = post("/api/v1/auth/refresh", body = """{"refresh_token":"${hr.refreshToken}"}""")
        assertEquals(200, refreshed.status)
        assertEquals(200, get("/api/v1/users/me", refreshed.read("$.data.access_token")).status)

        assertEquals(204, post("/api/v1/auth/logout", hr.token, """{"refresh_token":"${hr.refreshToken}"}""").status)
        assertEquals(401, post("/api/v1/auth/refresh", body = """{"refresh_token":"${hr.refreshToken}"}""").status)
    }

    @Test
    fun `초대 코드 가입은 코드의 회사와 역할을 따르고 코드는 한 번만 쓸 수 있다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val code = newInviteCode(hr, "MENTOR")

        val joined = signupInvite(code, dept, "김멘토")
        assertEquals(201, joined.status)
        assertEquals("MENTOR", joined.read("$.data.role"))
        assertEquals(hr.companyId, joined.read("$.data.workspace.id"))

        assertEquals(409, signupInvite(code, dept, "다른사람").status)
        assertEquals(400, signupInvite("NOSUCHCODE", dept, "다른사람").status)
    }

    @Test
    fun `폐기된 초대 코드로는 가입할 수 없다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val code = newInviteCode(hr, "NEWBIE")
        val id: String = get("/api/v1/invite-codes?status=ACTIVE", hr.token).read("$.data.items[0].id")

        assertEquals(204, delete("/api/v1/invite-codes/$id", hr.token).status)
        assertEquals(400, signupInvite(code, dept, "신입").status)
        assertEquals("REVOKED", get("/api/v1/invite-codes", hr.token).read("$.data.items[0].status"))
    }

    @Test
    fun `프로필 수정과 비밀번호 변경`() {
        val email = email()
        val hr = newCompany(email)

        val updated = patch("/api/v1/users/me", hr.token, """{"name":"새이름","position":"팀장"}""")
        assertEquals(200, updated.status)
        assertEquals("새이름", updated.read("$.data.profile.name"))
        assertEquals(403, patch("/api/v1/users/me", hr.token, """{"role":"NEWBIE"}""").status)

        fun change(current: String, new: String) =
            put("/api/v1/users/me/password", hr.token, """{"current_password":"$current","new_password":"$new"}""").status
        assertEquals(401, change("wrong-password", "newpassword1!"))
        assertEquals(400, change("password1!", "short"))
        assertEquals(204, change("password1!", "newpassword1!"))
        assertEquals(200, post("/api/v1/auth/login", body = """{"email":"$email","password":"newpassword1!"}""").status)
        assertNotEquals(200, post("/api/v1/auth/login", body = """{"email":"$email","password":"password1!"}""").status)
    }
}
