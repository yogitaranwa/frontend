/**
 * AuthMapper.kt
 * Responsibility : Maps auth DTOs to domain models. No business logic — pure mapping.
 * API calls      : none (mapping only)
 * Injects        : none (object with extension functions)
 */
package com.artgrid.mobile.domain.auth.mapper

import com.artgrid.mobile.data.auth.dto.AuthResponseDto
import com.artgrid.mobile.data.auth.dto.RefreshResponseDto
import com.artgrid.mobile.domain.auth.model.AuthSession

object AuthMapper {

    /** Maps the full sign-in response to domain [AuthSession]. */
    fun AuthResponseDto.toDomain(): AuthSession = AuthSession(
        accessToken = accessToken,
        expiresIn   = expiresIn,
        userId      = userId,
        email       = email,
        isDemo      = isDemo,
    )

    /**
     * Maps the slim refresh response to [AuthSession].
     * [userId] and [email] are not returned on refresh — preserved as null.
     * [isDemo] is inferred from [AuthSession.userId] being null, but kept false
     * here because a stored demo token never calls /refresh (it's always valid).
     */
    fun RefreshResponseDto.toDomain(): AuthSession = AuthSession(
        accessToken = accessToken,
        expiresIn   = expiresIn,
        userId      = null,
        email       = null,
        isDemo      = false,
    )
}
