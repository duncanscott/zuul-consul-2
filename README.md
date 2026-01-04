# Zuul Consul Gateway 2

A lightweight API gateway built on **Netflix Zuul 2** with **Consul-based service discovery** and a simple, expressive **URL tag routing model**.

This project is designed for platform / SRE / backend engineering teams who want a **stable, observable, debuggable routing layer** — without adopting a full service mesh. It is based on operational experience gained from years of running Zuul 1 and Consul routing in production environments.

---

## ✨ Key Features

### 🚏 URL Tag–Based Routing
Route requests to services using clear, human‑readable tags in the path:

```
/env:dev/my-service/api/users
/env:prod/version:v2/my-service/health
/my-service/api/status          ← default tags apply
```

Tags such as `env:dev` or `version:v2` determine how the request is routed via Consul‑registered services.

This makes it easy to:
✔ switch environments  
✔ test new versions  
✔ perform gradual migrations  
✔ debug routing behavior

---

### 🔍 Consul‑Powered Service Discovery
- Uses **Consul health checks** to determine eligible backends
- Caches state locally for performance
- Supports real‑time Consul updates

---

### ⚖️ Load Balancing
- Simple round‑robin
- Avoids unhealthy instances
- No per‑request Consul lookups

---

### 🏗 Built on Zuul 2
Zuul 2 provides:
- Non‑blocking async architecture (Netty)
- High throughput
- Stability under load

This project uses Zuul as a dependency — not a fork — so you benefit from upstream stability without inheriting codebase complexity.

---

## 🚀 Quick Start

Clone and build:

```
git clone https://github.com/duncanscott/zuul-consul-2.git
cd zuul-consul-2
./gradlew build
```

Run the gateway (pointing at your Consul agent or the included demo stack):

```
export ZUUL_CONSUL_AGENT_HOST=localhost
export ZUUL_CONSUL_AGENT_PORT=8500
export ZUUL_DEFAULT_ENVIRONMENT=dev
./gradlew :app:run
```

See **Demo Stack & Functional Tests** below for a turnkey Consul + service lab that exercises these commands end-to-end, plus `./functional-tests.sh` which boots the stack and runs tests in one step.

---

## 🧪 Demo Stack & Functional Tests

A ready-to-use Docker Compose environment lives in `docker/docker-compose.yml`. Run it together with the gateway and functional specs via the single command:

```
./functional-tests.sh
```

The script boots Consul plus the sample services, waits for health checks, exports the standard `ZUUL_*` variables, launches `:app:run`, executes `:app:functionalTest`, and performs cleanup.

If you prefer to drive components manually, you can start the stack (Consul, `hello-service` for `env:dev` and `env:test`, `echo-service`, and optional nginx proxy) with:

```
./docker/start-test-env.sh
```

Then run the gateway locally (see Quick Start) to route through the containerized services at `http://localhost:9091`, and stop everything afterwards via `./docker/stop-test-env.sh`.

With the stack running, the functional suite can also be invoked directly:

```
./gradlew :app:functionalTest
```

Enable nginx-specific traffic flows by starting the proxy profile and running the targeted specs:

```
docker-compose -f docker/docker-compose.yml --profile nginx up -d
NGINX_TESTS=true ./gradlew :app:functionalTest --tests "*NginxProxySpec*"
```

Nginx listens on `http://localhost:8080` and `https://localhost:8443`, forwarding to the host's Zuul instance while applying the hardened headers and TLS handling described below.

---

## 🧠 Design Goals

### Keep It Simple
- Use Consul directly — no mesh, no sidecars, no control plane
- Routing logic should be readable in 10 seconds
- URL should reflect behavior

### Production‑Friendly
- Deterministic behavior
- Clear logging + debugging
- Failure‑aware routing

### Debuggable
If routing is confusing, the system has failed.  
So the design favors clarity first.

---

## 🔒 Deployment Model

Typical production deployment looks like:

```
Client → nginx (TLS, headers, limits) → Zuul Consul Gateway → Backend Services
```

Recommended nginx responsibilities:
- TLS termination
- CORS handling
- Logging overrides
- Request size enforcement
- Strip / rewrite trusted headers

Zuul focuses only on routing.

---

## 🧱 Example NGINX Configuration

The demo stack ships with a hardened reverse proxy in `docker/nginx-proxy/`. `nginx.conf` contains the global tuning, while `conf.d/zuul-consul.conf` defines the upstream keepalive block, proxy headers, gzip, and CORS / security response headers. The entrypoint script auto-generates self-signed certificates inside `docker/nginx-proxy/ssl/` (bring your own `server.crt` / `server.key` to override them).

Run the proxy alongside the services with:

```
docker-compose -f docker/docker-compose.yml --profile nginx up -d
```

and reach the gateway via `http://localhost:8080`, `https://localhost:8443`, or the status endpoint on `http://localhost:8888/health`. These same settings are exercised in `NginxProxySpec` when `NGINX_TESTS=true` during `:app:functionalTest`.

---

## 🏥 Health & Failure Behavior

| Scenario | Expected Behavior |
|---------|------------------|
| Consul unavailable | Continue routing using last known good state |
| Service unhealthy | Automatically removed from rotation |
| No matching service or tags | Request rejected cleanly |
| Long‑latency backend | Timed‑out and surfaced appropriately |

---

## 📡 Observability (Recommended)

You should configure:
- request IDs (`X‑Request‑Id`)
- structured logs including:
  - routing tags used
  - chosen service instance
  - request latency
  - upstream response code
- metrics per service + tag

Native logging + metric export guidance is coming soon.

---

## 🔍 Status & Intent

This project reflects real‑world operational experience at the **DOE Joint Genome Institute**, but this repository is a **fresh, clean rewrite** intended for wider use.

It intentionally avoids complexity — preferring predictable, transparent routing behavior over feature bloat.

---

## 📄 License

Apache License 2.0 — see `LICENSE`.

---

## 🙏 Acknowledgements

This project is built on the powerful foundations of **Netflix Zuul 2** and **HashiCorp Consul**.

---

## 🤝 Contributing

Contributions, feature discussions, and testing feedback are welcome.
Please open an Issue or Pull Request.

---

## 🧭 Roadmap

Planned additions include:

- Health endpoint specification
- Prometheus metrics support
- Example deployment configurations
- More documentation & diagrams

---

## 📬 Contact

Repository owner: **Duncan Scott**  
Project: **Zuul Consul Gateway 2**

---

If you find this useful — a star or share helps others discover it 🙂
