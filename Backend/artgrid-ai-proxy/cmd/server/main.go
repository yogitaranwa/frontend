// Package main is the entry point for artgrid-ai-proxy.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/artgrid/ai-proxy/internal/api"
	"github.com/artgrid/ai-proxy/internal/config"
	"github.com/artgrid/ai-proxy/internal/service"
)

func main() {
	cfg := config.Load()

	// Configure JSON logger.
	var level slog.Level
	switch strings.ToLower(cfg.LogLevel) {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	default:
		level = slog.LevelInfo
	}
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})).
		With("service", "artgrid-proxy"))

	chatSvc := service.NewChatService(cfg)
	router := api.NewRouter(cfg, chatSvc)

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      router,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 95 * time.Second, // > chi timeout (90s) to allow SSE flush
		IdleTimeout:  120 * time.Second,
	}

	go func() {
		slog.Info("server.starting", "port", cfg.Port, "demo_mode", cfg.DemoMode)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server.fatal", "error", err.Error())
			os.Exit(1)
		}
	}()

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
