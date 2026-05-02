// Package errors provides typed application errors with HTTP status codes.
package errors

import "fmt"

// AppError is the base error type. Handlers inspect Code for the HTTP status.
type AppError struct {
	Code    int
	Message string
	Err     error // internal cause — logged, never sent to client
}

func (e *AppError) Error() string { return e.Message }
func (e *AppError) Unwrap() error { return e.Err }

// Sentinel errors — handlers use errors.As() to match these.
var (
	ErrInvalidIDToken      = &AppError{Code: 401, Message: "invalid_id_token"}
	ErrTokenExpired        = &AppError{Code: 401, Message: "token_expired"}
	ErrGoogleJWKUnavail    = &AppError{Code: 503, Message: "google_jwk_unavailable"}
	ErrValidation          = &AppError{Code: 400, Message: "validation_failed"}
	ErrInternal            = &AppError{Code: 500, Message: "internal_server_error"}
)

// Wrapf creates a new AppError from a sentinel, attaching an internal cause.
func Wrapf(sentinel *AppError, cause error, format string, args ...any) *AppError {
	return &AppError{
		Code:    sentinel.Code,
		Message: sentinel.Message,
		Err:     fmt.Errorf(format+": %w", append(args, cause)...),
	}
}
