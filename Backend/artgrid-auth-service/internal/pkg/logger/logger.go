// Package logger provides a pre-configured slog.Logger with JSON output.
package logger

import (
	"log/slog"
	"os"
	"strings"
)

// New returns a JSON-format slog.Logger at the given level string.
// Required output fields: time, level, service, msg.
func New(level string) *slog.Logger {
	var l slog.Level
	switch strings.ToLower(level) {
	case "debug":
		l = slog.LevelDebug
	case "warn":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}

	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: l,
		// Rename the default "msg" key to "event" for consistency with structlog output.
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			if a.Key == slog.MessageKey {
				a.Key = "event"
			}
			return a
		},
	})).With("service", "artgrid-auth")
}
