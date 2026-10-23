package com.veycad.app

/** Read-only library row. Contains no graph, source map, decoded plane or mutable project. */
data class ProjectSummary(val id: String, val fps: Int, val displayName: String, val musicName: String?,
    val recipeId: String, val currentRevisionId: Long)
