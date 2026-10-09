@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.agentpanel.backend

import com.ayongw.idea.agentpanel.shared.ChatRepositoryRpcApi
import com.ayongw.idea.agentpanel.shared.SettingsRpcApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<ChatRepositoryRpcApi>()) {
            BackendChatRepositoryRpcApi()
        }
        remoteApi(remoteApiDescriptor<SettingsRpcApi>()) {
            BackendSettingsRpcApi()
        }
    }
}