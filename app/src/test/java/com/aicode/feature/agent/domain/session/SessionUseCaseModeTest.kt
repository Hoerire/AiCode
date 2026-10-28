package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 子会话模式：完全跟随父会话的有效模式，定义与参数都不能覆盖。
 *
 * 子代理是父会话的延伸，用户已用父会话模式表达授权级别；若再收窄，子代理会跑在更严的
 * 边界里，后台执行时卡在授权确认上，表现为「不可读不可写」。
 */
class SessionUseCaseModeTest {

    private val useCase = SessionUseCase(
        chatSessionDao = mockk<ChatSessionDao>(relaxed = true),
        agentMessageDao = mockk<AgentMessageDao>(relaxed = true)
    )

    private fun parent(mode: String, goalStatus: String? = null) = ChatSessionEntity(
        id = "p1",
        title = "parent",
        workspacePath = "~/workspace",
        createdAt = 0,
        updatedAt = 0,
        mode = mode,
        goalStatus = goalStatus
    )

    @Test
    fun child_modeEqualsParentMode() {
        assertEquals("PLAN", useCase.resolveChildMode("PLAN"))
        assertEquals("BUILD", useCase.resolveChildMode("BUILD"))
        assertEquals("AUTO", useCase.resolveChildMode("AUTO"))
    }

    @Test
    fun newSubSessionEntity_inheritsParentMode() {
        listOf(AgentMode.PLAN, AgentMode.BUILD, AgentMode.AUTO).forEach { mode ->
            val child = useCase.newSubSessionEntity(
                title = "sub",
                parentId = "p1",
                parent = parent(mode.name),
                subagentType = "writer"
            )
            assertEquals(mode.name, child.mode)
        }
    }

    /** goal 自主循环期间会话按 AUTO 运行，子代理必须同样拿到 AUTO，否则写操作会卡在授权确认。 */
    @Test
    fun newSubSessionEntity_usesAutoWhileGoalActive() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "p1",
            parent = parent(AgentMode.BUILD.name, GoalStatus.ACTIVE.name),
            subagentType = "writer"
        )
        assertEquals(AgentMode.AUTO.name, child.mode)
    }

    @Test
    fun newSubSessionEntity_keepsModeWhenGoalPaused() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "p1",
            parent = parent(AgentMode.BUILD.name, GoalStatus.PAUSED.name),
            subagentType = "writer"
        )
        assertEquals(AgentMode.BUILD.name, child.mode)
    }

    @Test
    fun newSubSessionEntity_keepsRawModeWhenUnparsable() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "p1",
            parent = parent("UNKNOWN"),
            subagentType = "writer"
        )
        assertEquals("UNKNOWN", child.mode)
    }
}
