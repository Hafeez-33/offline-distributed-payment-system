package com.demo.upimesh.mesh.session

import com.demo.upimesh.transport.BleProtocolException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MeshSyncStateMachineTest {

    @Test
    fun testValidFullReconciliationProgression() {
        val sm = MeshSyncStateMachine()
        assertEquals(MeshSyncState.IDLE, sm.currentState)

        sm.transitionTo(MeshSyncState.HELLO)
        assertEquals(MeshSyncState.HELLO, sm.currentState)

        sm.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
        assertEquals(MeshSyncState.SUMMARY_EXCHANGE, sm.currentState)

        sm.transitionTo(MeshSyncState.BUCKET_COMPARISON)
        assertEquals(MeshSyncState.BUCKET_COMPARISON, sm.currentState)

        sm.transitionTo(MeshSyncState.HASH_EXCHANGE)
        assertEquals(MeshSyncState.HASH_EXCHANGE, sm.currentState)

        sm.transitionTo(MeshSyncState.REQUESTING_PACKETS)
        assertEquals(MeshSyncState.REQUESTING_PACKETS, sm.currentState)

        sm.transitionTo(MeshSyncState.RECEIVING_PACKETS)
        assertEquals(MeshSyncState.RECEIVING_PACKETS, sm.currentState)

        sm.transitionTo(MeshSyncState.ACKNOWLEDGING)
        assertEquals(MeshSyncState.ACKNOWLEDGING, sm.currentState)

        sm.transitionTo(MeshSyncState.COMPLETED)
        assertEquals(MeshSyncState.COMPLETED, sm.currentState)
        assertTrue(sm.currentState.isTerminal())
    }

    @Test
    fun testShortCircuitConvergenceFromSummaryExchange() {
        val sm = MeshSyncStateMachine()
        sm.transitionTo(MeshSyncState.HELLO)
        sm.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
        sm.transitionTo(MeshSyncState.COMPLETED)
        assertEquals(MeshSyncState.COMPLETED, sm.currentState)
    }

    @Test
    fun testIllegalTransitionThrowsBleProtocolException() {
        val sm = MeshSyncStateMachine()
        assertThrows(BleProtocolException::class.java) {
            sm.transitionTo(MeshSyncState.COMPLETED)
        }
        assertEquals(MeshSyncState.FAILED, sm.currentState)
    }

    @Test
    fun testCannotTransitionFromTerminalState() {
        val sm = MeshSyncStateMachine()
        sm.transitionTo(MeshSyncState.HELLO)
        sm.transitionTo(MeshSyncState.SUMMARY_EXCHANGE)
        sm.transitionTo(MeshSyncState.COMPLETED)

        assertThrows(BleProtocolException::class.java) {
            sm.transitionTo(MeshSyncState.HELLO)
        }
    }

    @Test
    fun testFailTransitionFromAnyState() {
        val sm = MeshSyncStateMachine()
        sm.transitionTo(MeshSyncState.HELLO)
        sm.fail("Timeout")
        assertEquals(MeshSyncState.FAILED, sm.currentState)
    }
}
