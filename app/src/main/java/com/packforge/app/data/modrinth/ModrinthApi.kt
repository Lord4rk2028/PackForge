package com.packforge.app.data.modrinth

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ModrinthApi {

    @GET("search")
    suspend fun searchProjects(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20,
        @Query("index") index: String = "relevance",
        @Query("facets") facets: String? = null
    ): ModrinthSearchResponse

    @GET("project/{id}/version")
    suspend fun getProjectVersions(
        @Path("id") projectId: String
    ): List<ModrinthVersionDto>
}
