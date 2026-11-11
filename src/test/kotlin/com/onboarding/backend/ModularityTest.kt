package com.onboarding.backend

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    @Test
    fun `모듈 간 순환 의존이 없다`() {
        ApplicationModules.of(BackendApplication::class.java).verify()
    }
}
