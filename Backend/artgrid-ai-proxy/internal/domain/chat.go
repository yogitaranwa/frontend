// Package domain defines types for the AI proxy service.
package domain

// ChatRequest is the validated inbound request body for POST /api/v1/chat.
type ChatRequest struct {
	Message   string  `json:"message"`    // max 2000 chars
	ImageB64  *string `json:"image_b64"`  // optional base64 JPEG, max 1MB encoded
	SessionID string  `json:"session_id"` // client-generated UUID
}
