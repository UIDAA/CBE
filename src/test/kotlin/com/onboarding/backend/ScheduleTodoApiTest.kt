package com.onboarding.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScheduleTodoApiTest : ApiTestSupport() {

    private fun schedule(s: Session, visibility: String, start: String, end: String, extra: String = "") = post(
        "/api/v1/schedules", s.token, """{"title":"일정","start_at":"$start","end_at":"$end","visibility":"$visibility"$extra}""",
    )

    private val range = "from=2027-03-01T00:00:00Z&to=2027-03-31T23:59:59Z"

    @Test
    fun `회사 일정은 멘토만 만들고 수정하면 행위자를 뺀 전원에게 필수 알림이 간다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val mentor = newMember(hr, "MENTOR", dept, "멘토")
        val newbie = newMember(hr, "NEWBIE", dept, "신입")
        val (start, end) = "2027-03-10T01:00:00Z" to "2027-03-10T02:00:00Z"

        assertEquals(403, schedule(newbie, "COMPANY", start, end).status)
        assertEquals(403, schedule(hr, "COMPANY", start, end).status)
        val created = schedule(mentor, "COMPANY", start, end)
        assertEquals(201, created.status)
        val id: String = created.read("$.data.schedule.id")
        val version: Int = created.read("$.data.schedule.version")

        assertEquals(1, get("/api/v1/schedules?$range", newbie.token).read<List<Any>>("$.data.items").size)
        assertEquals(403, patch("/api/v1/schedules/$id", newbie.token, """{"title":"x","version":$version}""").status)
        assertEquals(409, patch("/api/v1/schedules/$id", mentor.token, """{"title":"x","version":${version + 5}}""").status)

        val updated = patch("/api/v1/schedules/$id", mentor.token, """{"start_at":"2027-03-10T05:00:00Z","end_at":"2027-03-10T06:00:00Z","version":$version}""")
        assertEquals(200, updated.status)
        assertEquals(version + 1, updated.read<Int>("$.data.schedule.version"))

        fun scheduleNotis(s: Session) = get("/api/v1/notifications?type=company_schedule", s.token)
        assertEquals(1, scheduleNotis(newbie).read<Int>("$.data.pagination.total_elements"))
        assertEquals(true, scheduleNotis(newbie).read("$.data.items[0].mandatory"))
        assertEquals(1, scheduleNotis(hr).read<Int>("$.data.pagination.total_elements"))
        assertEquals(0, scheduleNotis(mentor).read<Int>("$.data.pagination.total_elements"))

        assertEquals(403, delete("/api/v1/schedules/$id", hr.token).status)
        assertEquals(204, delete("/api/v1/schedules/$id", mentor.token).status)
        assertEquals(2, scheduleNotis(newbie).read<Int>("$.data.pagination.total_elements"))
    }

    @Test
    fun `개인 일정은 작성자에게만 보이고 작성자만 고칠 수 있다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val newbie = newMember(hr, "NEWBIE", dept, "신입")
        val (start, end) = "2027-03-05T01:00:00Z" to "2027-03-05T02:00:00Z"

        assertEquals(400, schedule(newbie, "PERSONAL", end, start).status)
        val id: String = schedule(newbie, "PERSONAL", start, end).read("$.data.schedule.id")

        assertEquals(1, get("/api/v1/schedules?$range", newbie.token).read<List<Any>>("$.data.items").size)
        assertEquals(0, get("/api/v1/schedules?$range", hr.token).read<List<Any>>("$.data.items").size)
        assertEquals(0, get("/api/v1/schedules?$range&visibility=COMPANY", newbie.token).read<List<Any>>("$.data.items").size)
        assertEquals(400, get("/api/v1/schedules?from=2027-03-31T00:00:00Z&to=2027-03-01T00:00:00Z", newbie.token).status)

        assertEquals(403, delete("/api/v1/schedules/$id", hr.token).status)
        assertEquals(404, delete("/api/v1/schedules/$id", newCompany().token).status)
        assertEquals(204, delete("/api/v1/schedules/$id", newbie.token).status)
        assertEquals(0, get("/api/v1/notifications", hr.token).read<Int>("$.data.pagination.total_elements"))
    }

    @Test
    fun `반복 일정은 조회 기간 안의 회차로 펼쳐진다`() {
        val hr = newCompany()
        assertEquals(400, schedule(hr, "PERSONAL", "2027-03-01T01:00:00Z", "2027-03-01T02:00:00Z", ""","recurrence_rule":"NOT-A-RULE"""").status)
        val created = schedule(hr, "PERSONAL", "2027-03-01T01:00:00Z", "2027-03-01T02:00:00Z", ""","recurrence_rule":"FREQ=WEEKLY","holiday_policy":"SKIP"""")
        assertEquals(201, created.status)

        val items = get("/api/v1/schedules?$range", hr.token)
        assertEquals(
            listOf("2027-03-01T01:00:00Z", "2027-03-08T01:00:00Z", "2027-03-15T01:00:00Z", "2027-03-22T01:00:00Z", "2027-03-29T01:00:00Z"),
            items.read<List<String>>("$.data.items[*].start_at"),
        )
        assertEquals("SKIP", items.read("$.data.items[0].holiday_policy"))
    }

    @Test
    fun `투두는 본인만 조회하고 수정하고 삭제한다`() {
        val hr = newCompany()
        val dept = newDepartment(hr)
        val newbie = newMember(hr, "NEWBIE", dept, "신입")

        assertEquals(400, post("/api/v1/todos", newbie.token, """{"title":"${"가".repeat(51)}"}""").status)
        val created = post("/api/v1/todos", newbie.token, """{"title":"서류 제출","due_date":"2027-03-05","memo":"인사팀"}""")
        assertEquals(201, created.status)
        val id: String = created.read("$.data.todo.id")
        post("/api/v1/todos", newbie.token, """{"title":"기한 없는 일"}""")

        assertEquals(2, get("/api/v1/todos", newbie.token).read<Int>("$.data.pagination.total_elements"))
        assertEquals(1, get("/api/v1/todos?date_from=2027-03-01&date_to=2027-03-31", newbie.token).read<Int>("$.data.pagination.total_elements"))
        assertEquals(0, get("/api/v1/todos", hr.token).read<Int>("$.data.pagination.total_elements"))

        assertEquals(403, patch("/api/v1/todos/$id", hr.token, """{"completed":true}""").status)
        assertEquals(404, patch("/api/v1/todos/$id", newCompany().token, """{"completed":true}""").status)
        assertEquals(true, patch("/api/v1/todos/$id", newbie.token, """{"completed":true}""").read("$.data.todo.completed"))
        assertEquals(1, get("/api/v1/todos?completed=true", newbie.token).read<Int>("$.data.pagination.total_elements"))

        assertEquals(403, delete("/api/v1/todos/$id", hr.token).status)
        assertEquals(204, delete("/api/v1/todos/$id", newbie.token).status)
        assertEquals(1, get("/api/v1/todos", newbie.token).read<Int>("$.data.pagination.total_elements"))
    }
}
