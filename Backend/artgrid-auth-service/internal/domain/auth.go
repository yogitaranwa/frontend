// Package domain defines all domain types for the auth service.
// No external imports — pure value objects.
package domain

import "time"

// User represents an authenticated ArtGrid user in deployed mode.
// In demo mode this struct is never persisted.
type User struct {
	ID        string    // UUID
	GoogleSub string    // Google subject identifier (stored AES-256 encrypted)
	Email     string    // PII (stored AES-256 encrypted)
	CreatedAt time.Time
	UpdatedAt time.Time
	DeletedAt *time.Time // nil = active
}

// AuthResponse is the canonical response shape for both auth endpoints.
type AuthResponse struct {
	AccessToken string  `json:"access_token"`
	TokenType   string  `json:"token_type"`
	ExpiresIn   int     `json:"expires_in"`
	UserID      *string `json:"user_id"`
	Email       *string `json:"email"`
	IsDemo      bool    `json:"is_demo"`
}

// RefreshResponse is the slim response for POST /api/v1/auth/refresh.
type RefreshResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
}

// SignInRequest is the validated request body for POST /api/v1/auth/google.
type SignInRequest struct {
	IDToken string `json:"id_token"`
}

// GoogleClaims holds the fields extracted from a validated Google ID token.
type GoogleClaims struct {
	Sub   string // Google subject identifier
	Email string
}
