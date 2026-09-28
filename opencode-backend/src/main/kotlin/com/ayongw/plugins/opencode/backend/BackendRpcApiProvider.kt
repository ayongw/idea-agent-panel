@file:Suppress("UnstableApiUsage")

package com.ayongw.plugins.opencode.backend

import com.ayongw.plugins.opencode.shared.ChatRepositoryRpcApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<ChatRepositoryRpcApi>()) {
            BackendChatRepositoryRpcApi()
        }
    }
}