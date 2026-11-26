package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable

// What is kept for the person to use in jobs: their specialties (lib/specialties.ts in
// jarvis-voice-agent) and projects (lib/projects.ts). Each needs `memory.read`.

@Serializable data class SpecialtyList(val specialties: List<Named> = emptyList())

@Serializable data class ProjectList(val projects: List<Named> = emptyList())

/** Their specialties, by name, for a job to follow one. */
suspend fun Hub.specialtyNames(): List<Named> = get<SpecialtyList>("/api/memory/specialties").specialties

/** Their projects, by name, for a job to be part of one. */
suspend fun Hub.projectNames(): List<Named> = get<ProjectList>("/api/v1/projects").projects
