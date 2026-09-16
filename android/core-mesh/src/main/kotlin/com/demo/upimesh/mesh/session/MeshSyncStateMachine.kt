package com.demo.upimesh.mesh.session

import com.demo.upimesh.transport.BleProtocolError
import com.demo.upimesh.transport.BleProtocolException

/**
 * Enforces valid state transitions and rejects out-of-order or illegal protocol transitions.
 */
class MeshSyncStateMachine(
    initialState: MeshSyncState = MeshSyncState.IDLE
) {
    var currentState: MeshSyncState = initialState
        private set

    val history: MutableList<MeshSyncState> = mutableListOf(initialState)

    /**
     * Attempts to transition to the requested targetState.
     * Throws BleProtocolException if the transition is illegal.
     */
    @Synchronized
    fun transitionTo(targetState: MeshSyncState) {
        if (currentState.isTerminal()) {
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                "Cannot transition from terminal state $currentState to $targetState"
            )
        }

        if (isValidTransition(currentState, targetState)) {
            currentState = targetState
            history.add(targetState)
        } else {
            val errorMsg = "Illegal state transition from $currentState to $targetState"
            currentState = MeshSyncState.FAILED
            history.add(MeshSyncState.FAILED)
            throw BleProtocolException(
                BleProtocolError.INVALID_MSG_TYPE,
                errorMsg
            )
        }
    }

    /**
     * Transitions unconditionally to FAILED upon error or timeout.
     */
    @Synchronized
    fun fail(reason: String? = null) {
        if (!currentState.isTerminal()) {
            currentState = MeshSyncState.FAILED
            history.add(MeshSyncState.FAILED)
        }
    }

    companion object {
        fun isValidTransition(from: MeshSyncState, to: MeshSyncState): Boolean {
            if (to == MeshSyncState.FAILED) return true

            return when (from) {
                MeshSyncState.IDLE -> to == MeshSyncState.HELLO
                MeshSyncState.HELLO -> to == MeshSyncState.SUMMARY_EXCHANGE
                MeshSyncState.SUMMARY_EXCHANGE -> to == MeshSyncState.BUCKET_COMPARISON || to == MeshSyncState.ACKNOWLEDGING || to == MeshSyncState.COMPLETED
                MeshSyncState.BUCKET_COMPARISON -> to == MeshSyncState.HASH_EXCHANGE || to == MeshSyncState.ACKNOWLEDGING || to == MeshSyncState.COMPLETED
                MeshSyncState.HASH_EXCHANGE -> to == MeshSyncState.REQUESTING_PACKETS ||
                        to == MeshSyncState.RECEIVING_PACKETS ||
                        to == MeshSyncState.ACKNOWLEDGING ||
                        to == MeshSyncState.COMPLETED
                MeshSyncState.REQUESTING_PACKETS -> to == MeshSyncState.RECEIVING_PACKETS ||
                        to == MeshSyncState.ACKNOWLEDGING ||
                        to == MeshSyncState.COMPLETED
                MeshSyncState.RECEIVING_PACKETS -> to == MeshSyncState.REQUESTING_PACKETS ||
                        to == MeshSyncState.ACKNOWLEDGING ||
                        to == MeshSyncState.COMPLETED
                MeshSyncState.ACKNOWLEDGING -> to == MeshSyncState.COMPLETED
                MeshSyncState.COMPLETED -> false
                MeshSyncState.FAILED -> false
            }
        }
    }
}
