# MariaAlpha Helm Chart

Umbrella Helm chart that deploys the full MariaAlpha trading platform — 7 application services + UI + Postgres + Kafka + Redis + Prometheus/Grafana/Loki/Tempo/Alloy — on a Kubernetes cluster.

`values.yaml` defaults are tuned for a single-node **OrbStack** cluster. Override with `-f values-<env>.yaml` for cloud targets.

## Prerequisites

- A running Kubernetes cluster (this chart targets >= 1.28). Tested on OrbStack 1.x.
- `kubectl` configured to talk to the cluster.
- `helm` 3.13+ or 4.x.
- All eight images present in the cluster's container runtime, tagged `mariaalpha/<svc>:local`. On OrbStack the local Docker daemon is shared with K8s, so `just build` is enough.

## Quickstart (OrbStack)

```bash
orb start k8s
just build
just k8s-up
```

The umbrella chart creates four namespaces (`mariaalpha`, `mariaalpha-data`, `mariaalpha-o11y`, `mariaalpha-infra`) and waits for every pod to become Ready before returning.

After install:

```bash
kubectl -n mariaalpha get pods                    # all Running, all Ready
helm test mariaalpha -n mariaalpha --logs         # actuator-health + iceberg e2e
open http://mariaalpha.orb.local                  # UI
open http://grafana.mariaalpha.orb.local          # Grafana dashboards
```

## Iterative dev loop

```bash
# Rebuild one service:
docker build -t mariaalpha/execution-engine:local -f execution-engine/Dockerfile .
kubectl -n mariaalpha rollout restart deployment/execution-engine
kubectl -n mariaalpha logs -f deploy/execution-engine
```

## Uninstall

```bash
just k8s-down
```

This deletes the Helm release **and** the four namespaces — including PVCs. Use only on dev clusters.

## Secrets

Two modes are supported.

### Plain (default, local)

`values.yaml` ships `secrets.useSealedSecrets: false`. The chart renders plain `Secret` resources from the values:

```yaml
secrets:
  alpacaApiKeyId: ""
  alpacaApiSecretKey: ""
  mariaalphaApiKey: "local-dev-key"
  postgresPassword: "mariaalpha"
  grafanaAdminUser: "admin"
  grafanaAdminPassword: "admin"
```

Override on the CLI: `helm install … --set secrets.mariaalphaApiKey=$(openssl rand -hex 24)`.

### Sealed (production)

Flip `secrets.useSealedSecrets: true` and enable the controller via `sealed-secrets.enabled: true`. Author the manifests with `kubeseal` (see [docs/runbooks/helm-rotate-secrets.md](../../docs/runbooks/helm-rotate-secrets.md)) and commit them under `charts/mariaalpha/sealed-secrets/`.

## Overlays

| File | Purpose |
|---|---|
| `values.yaml`        | OrbStack-friendly defaults (this file). |
| `values-burst.yaml`  | Higher resource limits for load testing on a beefy host. |
| `values-oci.yaml`    | Reserved for the Oracle Cloud overlay (image registry, OCI block volumes, sealed-secrets). |

## What is in scope

This chart deploys MariaAlpha to a local Kubernetes cluster. The Bitnami Redis subchart wired into order-manager + execution-engine is the distributed position cache (cluster DNS: `redis-master.mariaalpha-data.svc.cluster.local:6379`, auth disabled for the local install). NetworkPolicies, HPA tuning, PDBs, and TLS via cert-manager `ClusterIssuer` are deferred to follow-up tickets.

## Layout

```
charts/mariaalpha/
├── Chart.yaml                       umbrella, declares 9 external deps
├── values.yaml                      local defaults
├── values-burst.yaml                load-test overlay
├── values-oci.yaml                  cloud overlay (placeholder)
├── README.md
├── templates/
│   ├── _helpers.tpl
│   ├── namespaces.yaml              creates the four namespaces
│   ├── secrets.yaml                 plain Secret resources (gated on !useSealedSecrets)
│   ├── sealed-secrets-loader.yaml   range-loads sealed-secrets/ (gated on useSealedSecrets)
│   ├── ingress.yaml                 host-based routing for UI, API, Grafana
│   ├── grafana-dashboards.yaml      ConfigMaps with grafana_dashboard label
│   └── tests/
│       ├── test-actuator-health.yaml   helm test hook
│       └── test-iceberg-e2e.yaml       helm test hook
├── files/grafana-dashboards/        shipped JSON dashboards
└── charts/                          local subcharts
    ├── api-gateway/
    ├── market-data-gateway/
    ├── strategy-engine/
    ├── execution-engine/
    ├── order-manager/
    ├── post-trade/
    ├── ml-signal-service/
    ├── ui/
    └── kafka-topics-init/           post-install Job
```

## Troubleshooting

```bash
# Show every pod across the four namespaces:
for ns in mariaalpha mariaalpha-data mariaalpha-o11y mariaalpha-infra; do
  echo "=== $ns ==="; kubectl -n $ns get pods
done

# Tail logs across every app pod:
kubectl -n mariaalpha logs -l app.kubernetes.io/part-of=mariaalpha --all-containers --tail=50 -f
```
