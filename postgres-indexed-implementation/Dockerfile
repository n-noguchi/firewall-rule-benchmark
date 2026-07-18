FROM golang:1.25-bookworm AS build

WORKDIR /src
COPY postgres-indexed-implementation/go.mod postgres-indexed-implementation/go.sum ./
RUN go mod download
COPY postgres-indexed-implementation/*.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/firewall-postgres-indexed .

FROM debian:bookworm-slim
WORKDIR /benchmark
COPY --from=build /out/firewall-postgres-indexed /benchmark/firewall-postgres-indexed
COPY postgres-indexed-implementation/run-batch /benchmark/run-batch
RUN chmod 0755 /benchmark/firewall-postgres-indexed /benchmark/run-batch

ENV MASTER_DATABASE_URL="postgres://firewall:firewall@db:5432/firewall?sslmode=disable"

EXPOSE 8080
HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=600 \
  CMD ["/benchmark/firewall-postgres-indexed", "health", "--server", "http://127.0.0.1:8080"]
ENTRYPOINT ["/benchmark/firewall-postgres-indexed"]
CMD ["serve", "--data", "/benchmark/data", "--listen", ":8080"]
