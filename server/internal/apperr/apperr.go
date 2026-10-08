// Package apperr defines the error type that crosses the service/HTTP boundary.
// Codes are the stable identifiers listed in docs/API.md.
package apperr

import (
	"errors"
	"net/http"
)

type Error struct {
	Status int
	Code   string
	Fields map[string]string
}

func (e *Error) Error() string { return e.Code }

func New(status int, code string) *Error { return &Error{Status: status, Code: code} }

func Validation(fields map[string]string) *Error {
	return &Error{Status: http.StatusBadRequest, Code: "validation_failed", Fields: fields}
}

func InvalidRequest() *Error  { return New(http.StatusBadRequest, "invalid_request") }
func NotFound() *Error        { return New(http.StatusNotFound, "not_found") }
func Unauthenticated() *Error { return New(http.StatusUnauthorized, "unauthenticated") }

// As returns the *Error in err's chain, if any.
func As(err error) (*Error, bool) {
	var e *Error
	ok := errors.As(err, &e)
	return e, ok
}
