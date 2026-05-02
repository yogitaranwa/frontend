// Package handler contains HTTP handlers for the auth service.
// Handlers validate request shape and call AuthService. No business logic here.
package handler

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/artgrid/auth-service/internal/domain"
	apperr "github.com/artgrid/auth-service/internal/pkg/errors"
	"github.com/artgrid/auth-service/internal/service"
	"github.com/go-chi/chi/v5/middleware"
)

// AuthHandler handles POST /api/v1/auth/google and POST /api/v1/auth/refresh.
type AuthHandler struct {
	authSvc *service.AuthService
}

// NewAuthHandler constructs AuthHandler with its dependency.
func NewAuthHandler(authSvc *service.AuthService) *AuthHandler {
	return &AuthHandler{authSvc: authSvc}
}

// SignInWithGoogle handles POST /api/v1/auth/google.
func (h *AuthHandler) SignInWithGoogle(w http.ResponseWriter, r *http.Request) {
	var req domain.SignInRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, r, &apperr.AppError{Code: 400, Message: "malformed_request_body"})
		return
	}

	resp, err := h.authSvc.SignInWithGoogle(r.Context(), req.IDToken)
	if err != nil {
		writeError(w, r, err)
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

// Refresh handles POST /api/v1/auth/refresh.
// The JWT middleware has already validated the token and placed userID + isDemo in ctx.
func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxKeyUserID).(string)
	isDemo := r.Context().Value(ctxKeyIsDemo).(bool)

	resp, err := h.authSvc.Refresh(r.Context(), userID, isDemo)
	if err != nil {
		writeError(w, r, err)
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

// Health handles GET /health.
func Health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "service": "artgrid-auth"})
}

// ── Context keys ──────────────────────────────────────────────────────────────

type contextKey string

const (
	ctxKeyUserID contextKey = "user_id"
	ctxKeyIsDemo contextKey = "is_demo"
)

// ContextKeys exported for use in middleware.
var (
	CtxKeyUserID = ctxKeyUserID
	CtxKeyIsDemo = ctxKeyIsDemo
)

// ── Helpers ───────────────────────────────────────────────────────────────────

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("response.encode_failed", "error", err.Error())
	}
}

func writeError(w http.ResponseWriter, r *http.Request, err error) {
	var appErr *apperr.AppError
	if !errors.As(err, &appErr) {
		appErr = apperr.ErrInternal
		slog.Error("unhandled_error", "trace_id", middleware.GetReqID(r.Context()), "error", err.Error())
	} else if appErr.Err != nil {
		// Log the internal cause but never send it to the client.
		slog.Warn("handler.app_error", "code", appErr.Code, "cause", appErr.Err.Error())
	}

	writeJSON(w, appErr.Code, map[string]string{
		"error":   appErr.Message,
		"message": httpMessage(appErr.Code, appErr.Message),
	})
}

func httpMessage(code int, key string) string {
	messages := map[string]string{
		"invalid_id_token":       "Google ID token validation failed",
		"token_expired":          "Token has expired — please sign in again",
		"google_jwk_unavailable": "Cannot reach Google auth servers — try again",
		"malformed_request_body": "Request body is not valid JSON or is missing required fields",
		"validation_failed":      "Request validation failed",
		"internal_server_error":  "An internal error occurred",
	}
	if msg, ok := messages[key]; ok {
		return msg
	}
	return http.StatusText(code)
}
