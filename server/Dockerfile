# syntax=docker/dockerfile:1
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/babytracker ./cmd/babytracker

# Static binary with embedded migrations and time-zone data; no shell, non-root.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/babytracker /babytracker
EXPOSE 8080
ENTRYPOINT ["/babytracker"]
CMD ["serve"]
