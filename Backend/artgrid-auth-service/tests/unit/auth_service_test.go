// Package service_test contains unit tests for AuthService.
// Repository is mocked — no real DB or network required.
package service_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/artgrid/auth-service/internal/config"
	"github.com/artgrid/auth-service/internal/domain"
	apperr "github.com/artgrid/auth-service/internal/pkg/errors"
	"github.com/artgrid/auth-service/internal/service"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

// ── Mock UserRepository ───────────────────────────────────────────────────────

type mockUserRepo struct{ mock.Mock }

func (m *mockUserRepo) UpsertUser(ctx context.Context, sub, email string) (*domain.User, error) {
	args := m.Called(ctx, sub, email)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*domain.User), args.Error(1)
}

func (m *mockUserRepo) FindByGoogleSub(ctx context.Context, sub string) (*domain.User, error) {
	args := m.Called(ctx, sub)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*domain.User), args.Error(1)
}

// ── Test helpers ──────────────────────────────────────────────────────────────

func demoConfig() *config.Config {
	return &config.Config{
		Port:      "8080",
		DemoMode:  true,
		JWTSecret: "test-secret-32-chars-minimum-pad",
	}
}

func deployedConfig() *config.Config {
	return &config.Config{
		Port:           "8080",
		DemoMode:       false,
		JWTSecret:      "test-secret-32-chars-minimum-pad",
		GoogleClientID: "test-client-id.apps.googleusercontent.com",
	}
}

func fakeUser() *domain.User {
	return &domain.User{
		ID:        "user-uuid-1234",
		GoogleSub: "google-sub-abc",
		Email:     "artist@example.com",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

// ── Test: Demo mode sign-in ───────────────────────────────────────────────────

func TestSignInWithGoogle_DemoMode_ReturnsJWT(t *testing.T) {
	repo := &mockUserRepo{}
	svc := service.NewAuthService(demoConfig(), repo)

	resp, err := svc.SignInWithGoogle(context.Background(), "")

	require.NoError(t, err)
	assert.NotEmpty(t, resp.AccessToken)
	assert.Equal(t, "Bearer", resp.TokenType)
	assert.Equal(t, 3600, resp.ExpiresIn)
	assert.True(t, resp.IsDemo)
	assert.Nil(t, resp.UserID)
	assert.Nil(t, resp.Email)
	// Verify the issued token can be validated by the same service.
	userID, isDemo, vErr := svc.ValidateToken(resp.AccessToken)
	require.NoError(t, vErr)
	assert.Equal(t, "demo-user-id", userID)
	assert.True(t, isDemo)
	// Repository must not be called in demo mode.
	repo.AssertNotCalled(t, "UpsertUser")
}

// ── Test: Refresh demo mode ───────────────────────────────────────────────────

func TestRefresh_DemoMode_ReturnsNewJWT(t *testing.T) {
	svc := service.NewAuthService(demoConfig(), &mockUserRepo{})

	resp, err := svc.Refresh(context.Background(), "demo-user-id", true)

	require.NoError(t, err)
	assert.NotEmpty(t, resp.AccessToken)
	assert.Equal(t, 3600, resp.ExpiresIn)
}

// ── Test: ValidateToken with tampered token ───────────────────────────────────

func TestValidateToken_TamperedToken_ReturnsError(t *testing.T) {
	svc := service.NewAuthService(demoConfig(), &mockUserRepo{})

	_, _, err := svc.ValidateToken("not.a.valid.jwt")
	require.Error(t, err)

	var appErr *apperr.AppError
	assert.True(t, errors.As(err, &appErr))
	assert.Equal(t, 401, appErr.Code)
}

// ── Test: Refresh deployed mode ───────────────────────────────────────────────

func TestRefresh_DeployedMode_UsesExistingUserID(t *testing.T) {
	svc := service.NewAuthService(deployedConfig(), &mockUserRepo{})

	resp, err := svc.Refresh(context.Background(), "user-uuid-1234", false)

	require.NoError(t, err)
	assert.NotEmpty(t, resp.AccessToken)

	// The issued token must carry the same user_id.
	userID, isDemo, vErr := svc.ValidateToken(resp.AccessToken)
	require.NoError(t, vErr)
	assert.Equal(t, "user-uuid-1234", userID)
	assert.False(t, isDemo)
}
