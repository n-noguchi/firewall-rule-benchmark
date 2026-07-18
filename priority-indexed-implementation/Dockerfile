FROM golang:1.25-bookworm AS build

WORKDIR /src
COPY priority-indexed-implementation/go.mod priority-indexed-implementation/go.sum ./
RUN go mod download
COPY priority-indexed-implementation/*.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/firewall-priority-indexed .

FROM debian:bookworm-slim
WORKDIR /benchmark
COPY --from=build /out/firewall-priority-indexed /benchmark/firewall-priority-indexed
COPY priority-indexed-implementation/run-batch /benchmark/run-batch
RUN chmod 0755 /benchmark/firewall-priority-indexed /benchmark/run-batch

EXPOSE 8080
HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=600 \
  CMD ["/benchmark/firewall-priority-indexed", "health", "--server", "http://127.0.0.1:8080"]
ENTRYPOINT ["/benchmark/firewall-priority-indexed"]
CMD ["serve", "--data", "/benchmark/data", "--master-db", "/benchmark/state/master.db", "--listen", ":8080"]
