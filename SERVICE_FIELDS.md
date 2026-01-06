# Service Instance Fields

This document lists all fields available from Consul for each service instance and indicates whether each field is published at the root endpoint (`/`).

## Field Reference

| Source | Field | Description | Published at "/" |
|--------|-------|-------------|------------------|
| **Service** | | | |
| Service.getName() | service | Service name | Yes |
| Service.getId() | id | Service instance ID | Yes |
| Service.getAddress() | address | Service address from Consul (may include protocol, e.g., `https://host`) | Yes |
| Service.getPort() | port | Service port | Yes |
| Service.getTags() | tags | Service tags list | Yes |
| Service.getMeta() | meta | Service metadata map | Yes |
| Service.getCreateIndex() | createIndex | Internal Consul index when created | No |
| Service.getModifyIndex() | modifyIndex | Internal Consul index when modified | No |
| **Node** | | | |
| Node.getName() | node | Node name | Yes |
| Node.getId() | node.id | Node UUID | Yes |
| Node.getAddress() | node.address | Node IP address | Yes |
| Node.getDatacenter() | datacenter | Consul datacenter | Yes |
| Node.getNodeMeta() | node.meta | Node metadata map | No |
| Node.getLanAddress() | node.lanAddress | Node LAN address | No |
| Node.getWanAddress() | node.wanAddress | Node WAN address | No |
| **Health Checks** | | | |
| ServiceEntry.getChecks() | checks | List of health checks | No |
| Check.getId() | check.id | Check ID | No |
| Check.getName() | check.name | Check name | No |
| Check.getStatus() | check.status | Check status (passing/warning/critical) | No |
| Check.getNotes() | check.notes | Human-readable notes | No |
| Check.getOutput() | check.output | Check output/message | No |
| ServiceEntry.aggregatedStatus() | healthStatus | Aggregated health status | Yes (as `healthy` boolean) |
| **Computed Fields** | | | |
| ConsulService.getUri() | url.full | Full service URL with context root | Yes |
| ConsulService.getContextRoot() | contextRoot | Parsed from `context-root!` tag | No |
| ConsulService.getDocsUrl() | docsUrl | Parsed from `docs!` tag | No |

## Example Output

```json
{
  "service_cache": {
    "services": {
      "dw-freezer-empty-containers": [
        {
          "service": "dw-freezer-empty-containers",
          "id": "dw-freezer-empty-containers:prd",
          "url.full": "https://prospero.jgi.doe.gov:443/ws/freezer-empty-containers",
          "address": "https://prospero.jgi.doe.gov",
          "port": 443,
          "healthy": true,
          "node": "prospero",
          "node.id": "9d0f85b8-ff11-1ace-e6ee-29391e6e9d40",
          "node.address": "128.3.122.17",
          "datacenter": "jgi",
          "meta": {},
          "tags": [
            "context-root!ws/freezer-empty-containers",
            "env:prd",
            "version:default"
          ]
        }
      ]
    }
  }
}
```

## Summary

- **13 fields** currently published at `/`
- **11 fields** available but not published

## Protocol Detection

The `url.full` field is computed using the following protocol detection priority:

1. **`meta["scheme"]`** - If the service metadata contains a `scheme` key, use its value (e.g., `"https"`, `"http"`). This aligns with Spring Cloud's `spring.cloud.consul.discovery.scheme`.
2. **`meta["secure"]`** - If the service metadata contains `"secure": "true"`, use `https`. This provides Spring Cloud compatibility via `spring.cloud.consul.discovery.metadata.secure=true`.
3. **Address prefix** - If the `address` field starts with `https://` or `http://`, use that protocol
4. **Default** - Use `http`

### Examples

| meta.scheme | meta.secure | address | Resulting protocol |
|-------------|-------------|---------|-------------------|
| `"https"` | (any) | `prospero.jgi.doe.gov` | `https` |
| `"https"` | (any) | `http://prospero.jgi.doe.gov` | `https` (scheme takes precedence) |
| (not set) | `"true"` | `prospero.jgi.doe.gov` | `https` |
| (not set) | `"false"` | `prospero.jgi.doe.gov` | `http` (default) |
| (not set) | (not set) | `https://prospero.jgi.doe.gov` | `https` |
| (not set) | (not set) | `prospero.jgi.doe.gov` | `http` (default) |

## Data Sources

Field data comes from the Vert.x Consul client library:
- `io.vertx.ext.consul.ServiceEntry`
- `io.vertx.ext.consul.Service`
- `io.vertx.ext.consul.Node`
- `io.vertx.ext.consul.Check`
