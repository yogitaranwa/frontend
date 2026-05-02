// Package middleware provides Chi-compatible HTTP middleware for the auth service.
package middleware

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/artgrid/auth-service/internal/api/handler"
	"github.com/artgrid/auth-service/internal/service"
	"github.com/go-chi/chi/v5/middleware"
)

// RequireJWT validates the Bearer token in the Authorization header.
// On success it places userID and isDemo in the request context.
func RequireJWT(authSvc *service.AuthService) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := extractBearer(r.Header.Get("Authorization"))
			if token == "" {
				writeUnauthorized(w, "missing_bearer_token")
				return
			}

			userID, isDemo, err := authSvc.ValidateToken(token)
			if err != nil {
				writeUnauthorized(w, "invalid_or_expired_token")
				return
			}

			ctx := context.WithValue(r.Context(), handler.CtxKeyUserID, userID)
			ctx = context.WithValue(ctx, handler.CtxKeyIsDemo, isDemo)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// StructuredLogger returns a Chi middleware that emits one slog line per request.
func StructuredLogger() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			start := time.Now()
			next.ServeHTTP(ww, r)
			slog.Info("http.request",
				"trace_id", middleware.GetReqID(r.Context()),
				"method", r.Method,
				"path", r.URL.Path,
				"status", ww.Status(),
				"latency_ms", time.Since(start).Milliseconds(),
				"bytes", ww.BytesWritten(),
			)
		})
	}
}

// ── Private helpers ───────────────────────────────────────────────────────────

func extractBearer(header string) string {
	if !strings.HasPrefix(header, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(header, "Bearer ")
}

func writeUnauthorized(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte(`{"error":"unauthorized","message":"` + msg + `"}`))
}
