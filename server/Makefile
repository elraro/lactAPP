# DB tests need BT_TEST_DATABASE_URL pointing at any PostgreSQL 17 server where the
# user may create databases, e.g. the "db" service of docker-compose.yml:
#   export BT_TEST_DATABASE_URL=postgres://babytracker:change-me@localhost:5432/postgres?sslmode=disable

.PHONY: check fmt lint test generate build

check: fmt lint test build

fmt:
	@test -z "$$(gofmt -l .)" || (gofmt -l . && echo "run: gofmt -w ." && exit 1)

lint:
	go vet ./...
	staticcheck ./...
	sqlc diff

test:
	go test -race -count=1 ./...

generate:
	sqlc generate

build:
	go build -o bin/babytracker ./cmd/babytracker
