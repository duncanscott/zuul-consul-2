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

> A full demo using Docker Compose is planned — for now, the project builds and runs via Gradle.

Clone and build:

```
git clone https://github.com/duncanscott/zuul-consul-2.git
cd zuul-consul-2
./gradlew build
```

Run:

```
./gradlew run
```

Point the gateway at a Consul agent and start routing requests.

Configuration defaults and examples will be added shortly.

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

- Docker demo stack (Gateway + Consul + sample services)
- Example nginx config
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
