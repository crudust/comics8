package com.comics8.core.source

import com.comics8.core.source.js.JsComicSource
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.network.NetworkProtocol

enum class SourceType(
    val label: String,
    val isStorage: Boolean,
) {
    LOCAL("로컬 저장소", isStorage = true),
    SMB("SMB 네트워크 드라이브", isStorage = true),
    WEBDAV("WebDAV 클라우드", isStorage = true),
    JS("JS 확장 스크립트", isStorage = false),
    WEB("내장 웹 소스", isStorage = false),
}

fun ComicSource.resolveSourceType(): SourceType {
    if (this.id == WorkId.LOCAL_SOURCE) return SourceType.LOCAL
    if (this is NetworkLibrarySource) {
        return when (this.config.protocol) {
            NetworkProtocol.SMB -> SourceType.SMB
            NetworkProtocol.WEBDAV -> SourceType.WEBDAV
        }
    }
    if (this is JsComicSource) return SourceType.JS
    if (this.id.startsWith("network-")) return SourceType.SMB
    return SourceType.WEB
}
