package dev.shuchir.hcgateway.domain.model

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val currentType: String, val typesCompleted: Int, val totalTypes: Int) : SyncState()
    data class Error(val message: String) : SyncState()
    data class Done(val recordCount: Int) : SyncState()
}
