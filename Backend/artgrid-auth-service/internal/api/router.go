// Package api wires the Chi router with all routes and middleware.
package api

import (
	"net/http"

	"github.com/artgrid/auth-service/internal/api/handler"
	"github.com/artgrid/auth-service/internal/api/middleware"
	"github.com/artgrid/auth-service/internal/config"
	"github.com/artgrid/auth-service/internal/service"
	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
)

// NewRouter constructs and returns the fully configured Chi router.
func NewRouter(cfg *config.Config, authSvc *service.AuthService) http.Handler {
	r := chi.NewRouter()

	// Global middleware stack.
	r.Use(chimw.RequestID)
	r.Use(chimw.RealIP)
	r.Use(middleware.StructuredLogger())
	r.Use(chimw.Recoverer)

	// Health check — no auth required.
	r.Get("/health", handler.Health)

	// Auth endpoints.
	authH := handler.NewAuthHandler(authSvc)
	r.Route("/api/v1/auth", func(r chi.Router) {
		r.Post("/google", authH.SignInWithGoogle)

		// /refresh requires a valid (non-expired) JWT.
		r.With(middleware.RequireJWT(authSvc)).Post("/refresh", authH.Refresh)
	})

	return r
}
