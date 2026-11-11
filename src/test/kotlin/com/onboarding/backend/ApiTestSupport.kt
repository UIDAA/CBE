package com.onboarding.backend

import com.jayway.jsonpath.JsonPath
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Res(val status: Int, val body: String) {
    fun <T> read(path: String): T = JsonPath.read(body, path)
}

data class Session(val token: String, val refreshToken: String, val userId: String, val companyId: String)

@Import(TestcontainersConfiguration::class)
@SpringBootTest
abstract class ApiTestSupport {
    @Autowired
    lateinit var context: WebApplicationContext

    private val mvc: MockMvc by lazy {
        MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    fun call(method: HttpMethod, path: String, token: String? = null, body: String? = null): Res {
        val req = MockMvcRequestBuilders.request(method, path)
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(it) }
        val res = mvc.perform(req).andReturn().response
        return Res(res.status, res.getContentAsString(Charsets.UTF_8))
    }

    fun get(path: String, token: String? = null) = call(HttpMethod.GET, path, token)
    fun post(path: String, token: String? = null, body: String? = null) = call(HttpMethod.POST, path, token, body)
    fun patch(path: String, token: String? = null, body: String? = null) = call(HttpMethod.PATCH, path, token, body)
    fun put(path: String, token: String? = null, body: String? = null) = call(HttpMethod.PUT, path, token, body)
    fun delete(path: String, token: String? = null, body: String? = null) = call(HttpMethod.DELETE, path, token, body)

    fun email() = "u${UUID.randomUUID().toString().take(12)}@example.com"
    fun future(days: Long = 1): String = Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun session(res: Res) = Session(
        res.read("$.data.tokens.access_token"), res.read("$.data.tokens.refresh_token"),
        res.read("$.data.user.id"), res.read("$.data.workspace.id"),
    )

    /** 회사를 만들고 HR 세션을 돌려준다. */
    fun newCompany(email: String = email(), password: String = "password1!"): Session {
        val res = post(
            "/api/v1/auth/signup/workspace",
            body = """{"company_name":"테스트회사","company_size":10,"industry":"IT","plan_type":"FREE",
                "email":"$email","password":"$password","password_confirm":"$password","name":"관리자"}""",
        )
        check(res.status == 201) { res.body }
        return session(res)
    }

    fun newDepartment(hr: Session, name: String = "부서-${UUID.randomUUID().toString().take(6)}"): String {
        val res = post("/api/v1/departments", hr.token, """{"name":"$name"}""")
        check(res.status == 201) { res.body }
        return res.read("$.data.department.id")
    }

    fun newInviteCode(hr: Session, role: String): String {
        val res = post("/api/v1/invite-codes", hr.token, """{"role":"$role","expires_at":"${future(7)}"}""")
        check(res.status == 201) { res.body }
        return res.read("$.data.invite_code")
    }

    fun signupInvite(code: String, departmentId: String, name: String, email: String = email(), password: String = "password1!") =
        post(
            "/api/v1/auth/signup/invite",
            body = """{"invite_code":"$code","email":"$email","password":"$password","password_confirm":"$password",
                "profile":{"name":"$name","position":"사원","department_id":"$departmentId","phone":"010-1234-5678"}}""",
        )

    /** 초대 코드로 가입한 구성원 세션. */
    fun newMember(hr: Session, role: String, departmentId: String, name: String): Session {
        val res = signupInvite(newInviteCode(hr, role), departmentId, name)
        check(res.status == 201) { res.body }
        return session(res)
    }
}
