package com.comics8.core.source

interface SourceHttp {
    fun fetch(spec: FetchSpec): HttpResult
    fun fetchText(spec: FetchSpec): String
    fun isAccessible(url: String, policy: RequestPolicy): Boolean
}
