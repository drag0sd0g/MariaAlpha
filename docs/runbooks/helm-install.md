# Runbook: First-time Helm install on a fresh cluster (OrbStack)

Target: a developer laptop with OrbStack installed and Docker daemon shared with K8s.

## 1. Prerequisites (one-time)

```bash
brew install helm kubeseal      # kubeseal only needed if useSealedSecrets=true
orb start k8s                   # ~10 s to a working cluster
kubectl get nodes               # NAME=orbstack, Ready
```

## 2. Build images

The umbrella chart's `values.yaml` references images tagged `mariaalpha/<svc>:local`. OrbStack's K8s shares the host Docker daemon, so `docker build` is enough — no registry push.

```bash
just build              # gradle + UI build, then docker compose build
# tag the two services whose docker-compose entries lack an explicit image tag:
docker tag mariaalpha-execution-engine:latest mariaalpha/execution-engine:local
docker tag mariaalpha-ml-signal-service:latest mariaalpha/ml-signal-service:local
docker images | grep '^mariaalpha/'
```

You should see eight images: `api-gateway, market-data-gateway, strategy-engine, execution-engine, order-manager, post-trade, ml-signal-service, ui`, all tagged `local`.

## 3. Pull chart dependencies

```bash
cd charts/mariaalpha
helm dependency update          # pulls postgres + kafka + prom-stack + loki + tempo + alloy + ingress-nginx + cert-manager + sealed-secrets
ls charts/                      # *.tgz packed dependencies appear here
```

## 4. Install

```bash
helm install mariaalpha . \
  --create-namespace -n mariaalpha \
  --wait --timeout 8m
```

The `--wait` flag holds until every Deployment is Ready. First install takes 3–5 minutes on a cold cluster (image extraction + Postgres initdb + Liquibase migrations + Kafka topic creation).

## 5. Verify

```bash
kubectl -n mariaalpha get pods                  # all Running, all 1/1 Ready
kubectl -n mariaalpha-data get pods              # postgresql, kafka, redis-master (issue 2.7.4)
kubectl -n mariaalpha-o11y get pods              # prometheus, grafana, loki, tempo, alloy
helm test mariaalpha -n mariaalpha --logs       # actuator-health + iceberg e2e pods
```

To inspect the Phase-2 distributed position cache:

```bash
kubectl -n mariaalpha-data exec -it redis-master-0 -- redis-cli KEYS 'mariaalpha:position:*'
kubectl -n mariaalpha-data exec -it redis-master-0 -- redis-cli GET 'mariaalpha:position:AAPL'
# Watch live updates from the order-manager:
kubectl -n mariaalpha-data exec -it redis-master-0 -- redis-cli SUBSCRIBE mariaalpha.positions.updates
```

Browser:

| URL | Expected |
|---|---|
| http://mariaalpha.orb.local            | UI dashboard renders |
| http://api.mariaalpha.orb.local/actuator/health | `{"status":"UP"}` |
| http://grafana.mariaalpha.orb.local    | Grafana login (admin/admin) → `MariaAlpha — Trading Pipeline` dashboard |

If `*.orb.local` is not resolving, fall back to port-forward:

```bash
kubectl -n mariaalpha port-forward svc/ui 5173:80 &
kubectl -n mariaalpha port-forward svc/api-gateway 8080:8080 &
```

## 6. Upgrade

```bash
helm upgrade mariaalpha charts/mariaalpha -n mariaalpha --wait --timeout 5m
```

Image-tag bumps:

```bash
helm upgrade mariaalpha charts/mariaalpha -n mariaalpha \
  --set global.images.tag=v0.2.5 \
  --wait --timeout 5m
```

## 7. Uninstall

```bash
helm uninstall mariaalpha -n mariaalpha
# helm uninstall does not delete PVCs — clean them up explicitly on dev clusters:
kubectl -n mariaalpha-data delete pvc --all
kubectl -n mariaalpha-o11y delete pvc --all
kubectl delete ns mariaalpha mariaalpha-data mariaalpha-o11y mariaalpha-infra
```

`just k8s-down` does all of the above in one shot.
