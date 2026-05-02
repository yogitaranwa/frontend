// Package main is the entry point for artgrid-auth-service.
// Responsibility : Wires config → DB → router → HTTP server with graceful shutdown.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/artgrid/auth-service/internal/api"
	"github.com/artgrid/auth-service/internal/config"
	"github.com/artgrid/auth-service/internal/pkg/logger"
	"github.com/artgrid/auth-service/internal/repository/postgres"
	"github.com/artgrid/auth-service/internal/service"
)

func main() {
	// Load and validate config — fails fast on missing required vars.
	cfg := config.Load()

	// Initialise structured JSON logger.
	log := logger.New(cfg.LogLevel)
	slog.SetDefault(log)

	// Connect to PostgreSQL (only in deployed mode; nil in demo mode).
	var userRepo service.UserRepository
	if !cfg.DemoMode {
		db := postgres.MustConnect(cfg.PostgresURL)
		defer db.Close()
		userRepo = postgres.NewUserRepository(db, cfg.PIIEncryptionKey)
		slog.Info("database.connected", "url_masked", cfg.MaskedPostgresURL())
	} else {
		userRepo = postgres.NewNoopUserRepository()
		slog.Info("demo_mode.active", "note", "no_database_connection")
	}

	// Wire up the service and router.
	authSvc := service.NewAuthService(cfg, userRepo)
	router := api.NewRouter(cfg, authSvc)

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      router,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start server in goroutine to allow graceful shutdown.
	go func() {
		slog.Info("server.starting", "port", cfg.Port, "demo_mode", cfg.DemoMode)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server.fatal", "error", err.Error())
			os.Exit(1)
		}
	}()

	// Graceful shutdown on SIGTERM / SIGINT.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)
	<-quit

	slog.Info("server.shutting_down")
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		slog.Error("server.shutdown_error", "error", err.Error())
		os.Exit(1)
	}
	slog.Info("server.stopped")
}
