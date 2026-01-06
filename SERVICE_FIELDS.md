# Service Instance Fields

This document lists all fields available from Consul for each service instance and indicates whether each field is published at the root endpoint (`/`).

## Field Reference

| Source | Field | Description | Published at "/" |
|--------|-------|-------------|------------------|
| **Service** | | | |
| Service.getName() | service | Service name | Yes |
| Service.getId() | id | Service instance ID | Yes |
| Service.getAddress() | address | Service address (may include protocol) | Yes (parsed) |
| Service.getPort() | port | Service port | Yes |
| Service.getTags() | tags | Service tags list | Yes |
| Service.getMeta() | meta | Arbitrary key-value metadata | No |
| Service.getCreateIndex() | createIndex | Internal Consul index when created | No |
| Service.getModifyIndex() | modifyIndex | Internal Consul index when modified | No |
| **Node** | | | |
| Node.getName() | node | Node name | Yes |
| Node.getId() | node.id | Node UUID | Yes |
| Node.getAddress() | node.address | Node IP address | Yes |
| Node.getDatacenter() | datacenter | Consul datacenter | Yes |
| Node.getLanAddress() | node.lanAddress | Node LAN address | No |
| Node.getWanAddress() | node.wanAddress | Node WAN address | No |
| Node.getNodeMeta() | node.meta | Node metadata map | No |
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
          "address": "prospero.jgi.doe.gov",
          "port": 443,
          "healthy": true,
          "node": "prospero",
          "node.id": "9d0f85b8-ff11-1ace-e6ee-29391e6e9d40",
          "node.address": "128.3.122.17",
          "datacenter": "jgi",
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

- **12 fields** currently published at `/`
- **12 fields** available but not published

## Data Sources

Field data comes from the Vert.x Consul client library:
- `io.vertx.ext.consul.ServiceEntry`
- `io.vertx.ext.consul.Service`
- `io.vertx.ext.consul.Node`
- `io.vertx.ext.consul.Check`
