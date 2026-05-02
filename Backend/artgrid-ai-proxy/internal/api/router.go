// Package api wires the Chi router for the AI proxy.
package api

import (
	"net/http"
	"time"

	"github.com/artgrid/ai-proxy/internal/api/handler"
	"github.com/artgrid/ai-proxy/internal/config"
	"github.com/artgrid/ai-proxy/internal/service"
	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"log/slog"
)

// NewRouter builds the fully configured Chi router for the AI proxy.
func NewRouter(cfg *config.Config, chatSvc *service.ChatService) http.Handler {
	r := chi.NewRouter()

	r.Use(chimw.RequestID)
	r.Use(chimw.RealIP)
	r.Use(structuredLogger())
	r.Use(chimw.Recoverer)
	r.Use(chimw.Timeout(90 * time.Second))

	chatH := handler.NewChatHandler(cfg, chatSvc)
	r.Get("/health", chatH.Health)
	r.Post("/api/v1/chat", chatH.Chat)

	return r
}

func structuredLogger() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ww := chimw.NewWrapResponseWriter(w, r.ProtoMajor)
			start := time.Now()
			next.ServeHTTP(ww, r)
			slog.Info("http.request",
				"trace_id", chimw.GetReqID(r.Context()),
				"method", r.Method,
				"path", r.URL.Path,
				"status", ww.Status(),
				"latency_ms", time.Since(start).Milliseconds(),
			)
		})
	}
}
