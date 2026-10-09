// Command babytracker is the Baby Tracker API server and its administration CLI.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	_ "time/tzdata" // zone data in the binary: the container image has none

	"github.com/jackc/pgx/v5/pgxpool"

	"babytracker/internal/ai"
	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/config"
	"babytracker/internal/httpapi"
	"babytracker/internal/store"
	"babytracker/internal/syncsvc"
)

const usage = `Usage:
  babytracker serve                      run migrations and start the API
  babytracker migrate                    run migrations and exit
  babytracker admin create-family -name N -timezone TZ -email E -display-name D
  babytracker admin reset-password -email E
`

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", describe(err))
		os.Exit(1)
	}
}

func describe(err error) string {
	if ae, ok := apperr.As(err); ok && len(ae.Fields) > 0 {
		return fmt.Sprintf("%s %v", ae.Code, ae.Fields)
	}
	return err.Error()
}

func run(args []string) error {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return errors.New("missing command")
	}
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return fmt.Errorf("configuration:\n%w", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	switch args[0] {
	case "migrate":
		return store.Migrate(ctx, cfg.DatabaseURL)
	case "serve":
		return serve(ctx, cfg)
	case "admin":
		return admin(ctx, cfg, args[1:])
	default:
		fmt.Fprint(os.Stderr, usage)
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func newLogger(cfg config.Config) *slog.Logger {
	var level slog.Level
	_ = level.UnmarshalText([]byte(cfg.LogLevel))
	opts := &slog.HandlerOptions{Level: level}
	if cfg.Production() {
		return slog.New(slog.NewJSONHandler(os.Stdout, opts))
	}
	return slog.New(slog.NewTextHandler(os.Stdout, opts))
}

func newAuth(cfg config.Config, pool *pgxpool.Pool) (*auth.TokenIssuer, *auth.Service, error) {
	issuer := auth.NewTokenIssuer(cfg.JWTSigningKey, cfg.AccessTokenTTL, time.Now)
	svc, err := auth.NewService(pool, issuer, cfg.RefreshTokenTTL, time.Now)
	return issuer, svc, err
}

func serve(ctx context.Context, cfg config.Config) error {
	logger := newLogger(cfg)
	if err := store.Migrate(ctx, cfg.DatabaseURL); err != nil {
		return err
	}
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	issuer, authSvc, err := newAuth(cfg, pool)
	if err != nil {
		return err
	}

	provider, err := ai.NewProvider(ai.Settings{Provider: cfg.AIProvider, Model: cfg.AIModel, APIKey: cfg.AIAPIKey, BaseURL: cfg.AIBaseURL})
	if err != nil {
		return fmt.Errorf("configuration:\n%w", err)
	}
	if provider != nil {
		logger.Info("ai provider configured", "provider", provider.Name(), "model", provider.Model())
	}
	aiSvc := ai.NewService(provider, store.New(pool), cfg.AIDailyLimit, time.Now, logger)

	srv := &http.Server{
		Addr: cfg.HTTPAddr,
		Handler: httpapi.New(httpapi.Deps{
			Pool: pool, Auth: authSvc, Sync: syncsvc.NewService(pool, time.Now), AI: aiSvc, Issuer: issuer,
			Logger: logger, Now: time.Now,
			RateLimits: httpapi.DefaultRateLimits, TrustedProxies: cfg.TrustedProxies,
		}),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	errc := make(chan error, 1)
	go func() { errc <- srv.ListenAndServe() }()
	logger.Info("listening", "addr", cfg.HTTPAddr, "env", cfg.Env)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return srv.Shutdown(shutdownCtx)
}

func admin(ctx context.Context, cfg config.Config, args []string) error {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, usage)
		return errors.New("missing admin command")
	}
	if err := store.Migrate(ctx, cfg.DatabaseURL); err != nil {
		return err
	}
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	_, authSvc, err := newAuth(cfg, pool)
	if err != nil {
		return err
	}

	switch args[0] {
	case "create-family":
		fs := flag.NewFlagSet("create-family", flag.ContinueOnError)
		var in auth.CreateFamilyInput
		fs.StringVar(&in.Name, "name", "", "family name")
		fs.StringVar(&in.Timezone, "timezone", "", "IANA time zone, e.g. Europe/Madrid")
		fs.StringVar(&in.Email, "email", "", "e-mail of the first parent")
		fs.StringVar(&in.DisplayName, "display-name", "", "name of the first parent")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		family, user, code, err := authSvc.CreateFamily(ctx, in)
		if err != nil {
			return err
		}
		fmt.Printf("Family created: %s\nUser created:   %s\n\n", family.ID, user.ID)
		printSetupCode(code)
		return nil
	case "reset-password":
		fs := flag.NewFlagSet("reset-password", flag.ContinueOnError)
		email := fs.String("email", "", "e-mail of the user")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		code, err := authSvc.ResetPassword(ctx, *email)
		if err != nil {
			return err
		}
		printSetupCode(code)
		return nil
	default:
		fmt.Fprint(os.Stderr, usage)
		return fmt.Errorf("unknown admin command %q", args[0])
	}
}

func printSetupCode(code string) {
	fmt.Printf("Password setup code (single use, valid 7 days):\n\n    %s\n\n"+
		"Enter it in the app under \"I have a code\" to choose a password.\n", code)
}
