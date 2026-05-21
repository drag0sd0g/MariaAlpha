# Cloud Deployment Plan

**Status:** Proposal — not yet executed. Awaiting user approval before creating issues, editing the TDD, or starting any deployment work.

**Constraint:** The full deployment MUST be free in perpetuity. Trial credits, time-limited free tiers, and paid fallbacks are explicitly out of scope.

**Decision required from this document:**
1. Approve removal of NG-5 from the TDD and the expansion of §10.
2. Approve the proposed Phase 2 re-prioritization.
3. Approve the platform choice: Oracle Cloud Always-Free (Ampere A1 + OKE).

---

## 1. Executive summary

We will deploy the full MariaAlpha stack — 7 application services + UI + PostgreSQL + Kafka + observability stack — to **Oracle Cloud Infrastructure (OCI) Always-Free tier**, using **Oracle Container Engine for Kubernetes (OKE)** as the orchestrator and a single **Ampere A1 Flex** ARM64 worker node (4 OCPU / 24 GB RAM / 200 GB block storage). Public ingress will be a free OCI Load Balancer fronted by NGINX Ingress Controller and cert-manager (Let's Encrypt). Domain will be a free `nip.io` wildcard mapped to the LB's reserved public IP. CI/CD will use GitHub Actions to build multi-arch Docker images (linux/amd64 + linux/arm64), push to GitHub Container Registry, and `helm upgrade` against the OKE cluster on every push to `main`.

The plan commits to Oracle because **it is the only provider in 2026 that offers a free-forever VM at the scale we need** (~5 vCPU / ~7 GiB requested). See §3 for the alternative-providers analysis.

Total time to complete: **~1–2 weeks of focused work**, broken into eight new GitHub issues plus expansions to two existing ones (#82, #83).

---

## 2. Sequencing decision

Issues 2.1.3 (IOC/FOK) and 2.1.4 (GTC/Iceberg) have **landed** on `main` (commits `87cbb54` and `cb96a14`). The branch is in a clean, mergeable state and the cloud-deployment milestone described in this document is the natural next milestone to open.

| Step | Status / Effort | Outcome |
| --- | --- | --- |
| ~~Finish 2.1.3 (IOC/FOK) + 2.1.4 (GTC/Iceberg)~~ | ✅ Merged (`87cbb54`, `cb96a14`) | Branch was at a clean, mergeable commit before the pivot |
| Open the cloud-deployment milestone (this document) | ~1–2 weeks | Every subsequent feature PR auto-deploys |
| Resume Phase 2 starting at 2.1.5 (TWAP) | — | Each merge ships to cloud |

Rationale (still load-bearing for the *next* mid-feature pivot decision): pivoting mid-feature leaves a half-built ExchangeAdapter behaviour set on the branch, which complicates the next PR's diff and obscures whether the cloud pipeline broke the new feature or the new feature broke itself. The same logic will apply when this document is revisited.

---

## 3. Why Oracle is the only viable free option in 2026

### 3.1 Alternatives evaluated and rejected

| Provider | Free offering in 2026 | Why insufficient |
| --- | --- | --- |
| AWS Free Tier | t2.micro/t3.micro: 1 vCPU / 1 GB RAM, expires after 12 months | RAM 7× too small. Expires. |
| Google Cloud Free Tier | e2-micro: 0.25 vCPU / 1 GB RAM, always-free | RAM 7× too small. |
| Azure Free Tier | B1S: 1 vCPU / 1 GB RAM, 12 months only | RAM 7× too small. Expires. |
| Fly.io | Free tier removed in 2024 — new accounts get a 2-VM-hour or 7-day trial, then credit card required | No real free tier remaining. |
| Koyeb | 1 instance with 0.1 vCPU / 512 MB RAM, no card required | RAM 14× too small. Cannot run even one of our Spring Boot services. |
| Render | Static sites free; web services spin down after 15 min idle with 30–60s cold start, paid tier required for always-on | Stateful services (Kafka, Postgres) cannot run; cold starts kill UX. |
| Railway | $5/month credit, then paid | Time-limited; insufficient for 10-service stack. |
| IBM Cloud Kubernetes | $200 sign-up credit, then paid | Credits expire. |
| DigitalOcean / Vultr / Linode / Hetzner / Kamatera | No free tier; cheapest VM is ~€4–6/month | Paid — out of scope. |
| Northflank / Cloudflare Workers / Vercel | Designed for stateless or single-service apps | Cannot host Kafka + Postgres + 10 services. |
| Self-hosted on home machine + Cloudflare Tunnel | Free | Defeats the "cloud" goal; dev machine must stay on; not a portfolio demonstration. |

### 3.2 Oracle Cloud Always-Free — what it actually gives us

| Resource | Always-Free allowance | Expiration | Our consumption |
| --- | --- | --- | --- |
| Ampere A1 Flex compute | Up to 4 OCPU + 24 GB RAM (ARM64), split arbitrarily across instances | None | 1× instance @ 4 OCPU / 24 GB |
| AMD VM compute | 2× VM.Standard.E2.1.Micro (1/8 OCPU, 1 GB RAM each) | None | Not used |
| Block Volume storage | 200 GB total across up to 5 volumes | None | ~100 GB across Postgres/Kafka/Prometheus/Loki |
| Block Volume backups | Up to 5 active snapshots, 50 GB total | None | Daily Postgres + Kafka snapshots |
| Object Storage | 20 GB | None | Helm chart artifacts, model backups |
| Outbound data transfer | 10 TB/month | None | <1 GB/day expected |
| Public reserved IPs | 2 | None | 1× ingress LB IP |
| Flexible Load Balancer | 1 × 10 Mbps shape | None | Ingress to NGINX |
| OKE control plane | Free for Basic clusters | None | 1× cluster |
| Autonomous Database | 2 × 20 GB ATP/ADW | None | Optional fallback for Postgres |
| Email Delivery | 1,000 sends/day | None | Optional for alerts |
| Notifications | 1M Slack/email/HTTPS deliveries/month | None | Used for cost alerts |
| Monitoring | 500M ingested datapoints, 1B retrieved | None | Used for OS-level metrics |
| Logging | 10 GB/month | None | Used for OKE control plane logs |
| Cloud Guard | Free | None | Daily security posture scan |
| Vault | 20 keys + 150 secrets | None | Used for app secrets |

**The "always-free" promise has held for 4+ years** for users we've seen reported. Oracle has not retroactively reclaimed Always-Free resources from active accounts.

### 3.3 The Ampere A1 capacity problem and its mitigation

Ampere A1 capacity has been chronically constrained for new free-tier accounts since launch in 2022 — but the situation in 2026 is:

| Region | Availability | Recommendation |
| --- | --- | --- |
| US East (Ashburn) | Heavily constrained — "Out of host capacity" common, can take days | Avoid |
| US West (Phoenix) | Heavily constrained | Avoid |
| **Frankfurt (eu-frankfurt-1)** | **Typically provisions within 5 minutes** | **Primary choice** |
| **Singapore (ap-singapore-1)** | **Typically provisions within 5 minutes** | **Alternative** |
| Tokyo, Sydney | Variable, sometimes constrained at peak | Backup |

**Why Frankfurt is the primary choice:**
- Lowest reported provisioning failures in 2026
- Latency to Alpaca US-east servers: ~85 ms — adds tolerable overhead to our >10ms tick processing budget
- Alpaca paper API has no geo-blocking from EU
- GDPR-compliant region, no transfer-out concerns for our use case

**Provisioning mitigation:**
- Use the [`hitrov/oci-arm-host-capacity`](https://github.com/hitrov/oci-arm-host-capacity) Node.js script — polls the OCI API every N seconds and creates the instance the moment capacity opens
- Run the script from a small VM (the free AMD micro VM works) or from a local laptop
- Typical wait time in Frankfurt as of 2026: minutes to a few hours
- Worst case: park the cloud milestone, retry weekly; the simulator stack remains the working dev environment

**Account setup gotcha:** The home region is selected at account signup and is non-trivial to change later (requires support ticket). Pick Frankfurt at signup.

---

## 4. Target architecture

```
                                 ┌──────────────────────────────────────────┐
                                 │            Internet                       │
                                 └────────────────┬──────────────────────────┘
                                                  │
                                                  ▼
                            ┌──────────────────────────────────────────┐
                            │  OCI Flexible Load Balancer (10 Mbps)    │
                            │  Reserved public IP: <IP>                │
                            │  Frontend: 80 (redirect→443), 443        │
                            └────────────────┬─────────────────────────┘
                                             │
                                             ▼
                  ┌─────────────────────────────────────────────────────┐
                  │ OKE Worker Node (Ampere A1 Flex, 4 OCPU / 24 GB)    │
                  │ Region: eu-frankfurt-1                              │
                  │ Subnet: 10.0.1.0/24 (public)                        │
                  │                                                     │
                  │  ┌─────────────────────────────────────────────┐    │
                  │  │ ingress-nginx (DaemonSet on hostNetwork)    │    │
                  │  └────────────────────┬────────────────────────┘    │
                  │                       │                              │
                  │  ┌────────────────────▼────────────────────┐        │
                  │  │  Ingress rules:                          │        │
                  │  │  - <IP>.nip.io       → ui:80             │        │
                  │  │  - api.<IP>.nip.io   → api-gateway:8080  │        │
                  │  │  - grafana.<IP>.nip.io → grafana:3000    │        │
                  │  └──────────────────────────────────────────┘        │
                  │                                                     │
                  │  ┌──────────────────────────────────────────────┐  │
                  │  │ Application namespace (mariaalpha)            │  │
                  │  │  api-gateway, ui, market-data-gw,            │  │
                  │  │  strategy-engine, execution-engine,           │  │
                  │  │  order-manager, post-trade, ml-signal-svc    │  │
                  │  └──────────────────────────────────────────────┘  │
                  │                                                     │
                  │  ┌──────────────────────────────────────────────┐  │
                  │  │ Stateful namespace (mariaalpha-data)          │  │
                  │  │  postgres (StatefulSet, 20 GB PV)             │  │
                  │  │  kafka (StatefulSet, 20 GB PV, KRaft mode)    │  │
                  │  └──────────────────────────────────────────────┘  │
                  │                                                     │
                  │  ┌──────────────────────────────────────────────┐  │
                  │  │ Observability namespace (mariaalpha-o11y)     │  │
                  │  │  prometheus (10 GB PV), loki (10 GB PV),     │  │
                  │  │  tempo (5 GB PV), alloy (DaemonSet),         │  │
                  │  │  grafana (1 GB PV)                            │  │
                  │  └──────────────────────────────────────────────┘  │
                  │                                                     │
                  │  ┌──────────────────────────────────────────────┐  │
                  │  │ Infrastructure namespace (mariaalpha-infra)   │  │
                  │  │  cert-manager (3 pods)                        │  │
                  │  │  external-secrets-operator (1 pod)            │  │
                  │  │  sealed-secrets-controller (1 pod, fallback)  │  │
                  │  └──────────────────────────────────────────────┘  │
                  └─────────────────────────────────────────────────────┘
                                             │
                                             ▼
                  ┌─────────────────────────────────────────────────────┐
                  │ External outbound (10 TB/month free)                │
                  │  - Alpaca paper WebSocket + REST                    │
                  │  - GHCR for image pulls                              │
                  │  - Let's Encrypt for cert renewal                   │
                  │  - OCI Vault for secrets                            │
                  └─────────────────────────────────────────────────────┘
```

---

## 5. Service-by-service deployment topology

We deviate from TDD §10.2 on replica counts (single-node cluster makes HPA pointless) and on retention windows (storage is capped at 200 GB across all PVs).

| Component | Replicas | CPU req | CPU limit | Mem req | Mem limit | PV size | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API Gateway | 1 | 250m | 500m | 256Mi | 512Mi | — | Drop HPA |
| Market Data GW | 1 | 500m | 1000m | 512Mi | 1Gi | — | Owns upstream WS |
| Strategy Engine | 1 | 500m | 1000m | 512Mi | 1Gi | — | Drop HPA |
| Execution Engine | 1 | 250m | 500m | 256Mi | 512Mi | — | |
| Order Manager | 1 | 250m | 500m | 256Mi | 512Mi | — | Drop HPA |
| Post-Trade | 1 | 250m | 500m | 256Mi | 512Mi | — | |
| ML Signal Service | 1 | 500m | 1000m | 1Gi | 2Gi | — | LightGBM model is <100 MB |
| Analytics Service | 0 | — | — | — | — | — | Not yet implemented |
| React UI | 1 | 100m | 250m | 128Mi | 256Mi | — | nginx static |
| PostgreSQL | 1 | 500m | 2000m | 1Gi | 4Gi | 20 GB | StatefulSet |
| Kafka (KRaft) | 1 | 500m | 1000m | 1Gi | 2Gi | 20 GB | StatefulSet; 24h tick retention vs 90d TDD |
| Prometheus | 1 | 250m | 500m | 512Mi | 1Gi | 10 GB | 7-day retention |
| Loki | 1 | 250m | 500m | 256Mi | 512Mi | 10 GB | 7-day retention |
| Tempo | 1 | 250m | 500m | 256Mi | 512Mi | 5 GB | 3-day retention |
| Alloy | 1 (DaemonSet) | 100m | 250m | 128Mi | 256Mi | — | |
| Grafana | 1 | 100m | 250m | 256Mi | 512Mi | 1 GB | Provisioned dashboards from configmap |
| cert-manager | 3 | 30m × 3 | 100m × 3 | 64Mi × 3 | 128Mi × 3 | — | |
| ingress-nginx | 1 | 100m | 500m | 128Mi | 256Mi | — | DaemonSet |
| ESO + sealed-secrets | 2 | 50m | 100m | 64Mi | 128Mi | — | |
| **Totals (requests)** | | **~4.93 vCPU** | | **~6.96 GiB** | | **76 GB** | Fits 4 OCPU / 24 GB / 200 GB |
| **Totals (limits)** | | **~10.4 vCPU** | | **~16.4 GiB** | | | Limits exceed node, deliberate — services rarely peak together |

**Storage breakdown (76 GB used of 200 GB free):**
- Postgres 20 GB, Kafka 20 GB, Prometheus 10 GB, Loki 10 GB, Tempo 5 GB, Grafana 1 GB
- Reserve 100 GB for: OS root volume (50 GB), Docker image cache (~30 GB), snapshot headroom (~20 GB)

**CPU over-commit:** Total requests ~4.93 vCPU on a 4 OCPU node. Kubernetes allows this; in steady state, average CPU usage is well under 50%. The over-commit is real only during simultaneous burst events (e.g., market open + ML model retrain).

---

## 6. Provisioning playbook

### 6.1 Account signup checklist

1. Sign up at [oracle.com/cloud/free](https://oracle.com/cloud/free) — requires credit card for identity verification (will not be charged unless you opt in to paid tier).
2. **Home region: select `Germany Central (Frankfurt)` or `Singapore`.** This is non-trivial to change later.
3. Add a budget alert at $1 (Console → Cost Management → Budgets) — early-warning if any resource leaves the free tier.
4. Enable Cloud Guard (free) — daily posture scan.
5. Create a compartment named `mariaalpha` to scope all resources.

### 6.2 VCN (Virtual Cloud Network) setup

Use the Console's "Setup VCN with Internet Connectivity" wizard, or apply via Terraform (see Appendix A). Configuration:
- CIDR: `10.0.0.0/16`
- Public subnet: `10.0.1.0/24` (for OKE nodes and LB)
- Internet Gateway attached
- Security list allowing:
  - Inbound TCP 80, 443 from `0.0.0.0/0` (HTTPS/HTTP for ingress)
  - Inbound TCP 22 from `<your-ip>/32` (SSH for debugging — restrict to your home IP)
  - Inbound TCP 6443 from `<your-ip>/32` (kubectl access)
  - Outbound all (default)

### 6.3 OKE cluster creation

Use Quick Create with these overrides:
- Cluster name: `mariaalpha-prod`
- Kubernetes version: 1.30+ (latest stable)
- Cluster type: **Basic** (free control plane; Enhanced costs money)
- Worker shape: `VM.Standard.A1.Flex`, 4 OCPU, 24 GB RAM
- Worker count: 1
- Boot volume: 50 GB
- Image: Oracle Linux 8 Ampere

**If "Out of host capacity":**
1. Clone `hitrov/oci-arm-host-capacity` to a small VM (or laptop).
2. Configure with API key + tenancy OCID (Console → Identity → Users → API Keys).
3. Run as a systemd service. It will create the node pool the moment capacity opens.
4. Typical wait in Frankfurt 2026: minutes to hours.

### 6.4 Persistent volume class

By default OKE provisions Block Volume PVs via `oci-bv` storage class. Confirm:
```bash
kubectl get storageclass
# NAME                PROVISIONER                  RECLAIMPOLICY
# oci-bv (default)    blockvolume.csi.oraclecloud.com   Delete
```
All our StatefulSet PVCs will use this — no further config needed.

### 6.5 Load Balancer

OKE automatically creates an OCI Load Balancer when we deploy a `Service` of type `LoadBalancer`. We will use a single LB created by the `ingress-nginx` Service annotation:
```yaml
service:
  type: LoadBalancer
  annotations:
    service.beta.kubernetes.io/oci-load-balancer-shape: "flexible"
    service.beta.kubernetes.io/oci-load-balancer-shape-flex-min: "10"
    service.beta.kubernetes.io/oci-load-balancer-shape-flex-max: "10"
```

The 10 Mbps flexible LB shape is free.

### 6.6 DNS — choosing `nip.io` (free)

`nip.io` resolves `<anything>.<IP>.nip.io` to `<IP>` — no registration, no DNS records to manage. Cost: $0.

Example: if our LB IP is `141.146.X.Y`, then:
- `141.146.X.Y.nip.io` → UI
- `api.141.146.X.Y.nip.io` → API Gateway
- `grafana.141.146.X.Y.nip.io` → Grafana

Trade-off vs a real domain (`mariaalpha.dev` at ~$15/year): nip.io URLs are uglier and not memorable. For a portfolio demo, acceptable.

Let's Encrypt issues certificates for nip.io subdomains without issue (the rate limit is per-domain not per-subdomain).

### 6.7 TLS — cert-manager + Let's Encrypt

Install cert-manager via Helm:
```bash
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace \
  --set installCRDs=true
```

Create a ClusterIssuer using HTTP-01 challenge (works with nip.io, no DNS provider needed):
```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: <user-email>
    privateKeySecretRef:
      name: letsencrypt-prod-key
    solvers:
      - http01:
          ingress:
            class: nginx
```

Each Ingress resource gets `cert-manager.io/cluster-issuer: letsencrypt-prod` and the cert is auto-renewed 30 days before expiry.

### 6.8 Secrets management

**Choice: sealed-secrets** (Bitnami controller).

Rationale: External Secrets Operator + OCI Vault is more "production-grade" but adds OCI API coupling and a multi-pod operator. For a single-environment demo, sealed-secrets is simpler:
- Encrypt a secret locally with `kubeseal` using the cluster's public key
- Commit the encrypted SealedSecret YAML to git (safe — only the cluster's controller can decrypt)
- The controller decrypts to a regular Secret at runtime

Workflow:
```bash
# One-time: install controller
helm install sealed-secrets sealed-secrets/sealed-secrets -n kube-system

# Per-secret:
kubectl create secret generic alpaca-creds \
  --from-literal=ALPACA_API_KEY_ID=$KEY \
  --from-literal=ALPACA_API_SECRET_KEY=$SECRET \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > helm/mariaalpha/templates/alpaca-creds-sealed.yaml
```

Secrets to manage:
- `MARIAALPHA_API_KEY` — gateway API key
- `ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` — broker creds
- `POSTGRES_PASSWORD` — DB password (Postgres StatefulSet env)
- `GRAFANA_ADMIN_PASSWORD` — Grafana admin

### 6.9 Backups

Postgres logical backups via a CronJob:
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: postgres-backup
spec:
  schedule: "0 2 * * *"  # 02:00 UTC daily
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: pgdump
              image: postgres:16-alpine
              command:
                - sh
                - -c
                - |
                  pg_dump -h postgres -U mariaalpha -d mariaalpha | \
                    gzip > /backup/pgdump-$(date +%F).sql.gz && \
                    find /backup -name 'pgdump-*' -mtime +7 -delete
              env:
                - name: PGPASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: postgres-password
                      key: PGPASSWORD
              volumeMounts:
                - name: backup-vol
                  mountPath: /backup
          volumes:
            - name: backup-vol
              persistentVolumeClaim:
                claimName: postgres-backup-pvc
          restartPolicy: OnFailure
```

PV-level snapshots via OCI Console (or `oci bv backup create` in a CronJob) for filesystem-consistent point-in-time recovery. Free tier: 5 active snapshots, 50 GB total — sufficient for weekly snapshots.

Kafka data is treated as ephemeral (24h retention); no backups.

---

## 7. CI/CD pipeline

### 7.1 Workflow inventory

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| `ci.yml` (existing) | PR + push | Lint, build, unit + integration tests |
| `docker-publish.yml` (#83 expanded) | Push to `main` | Build multi-arch images, push to GHCR |
| `deploy.yml` (new — 2.8.5) | After `docker-publish.yml` success on `main` | `helm upgrade` against OKE; post-deploy smoke |

### 7.2 `docker-publish.yml` — multi-arch GHCR push

Critical insight: **GitHub Actions now provides free ARM64 runners for public repos (`ubuntu-22.04-arm64`)** as of 2024. We will use them for native ARM builds instead of QEMU emulation (which is ~5× slower).

```yaml
name: Docker Publish
on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build-and-push:
    strategy:
      matrix:
        service:
          - api-gateway
          - market-data-gateway
          - strategy-engine
          - execution-engine
          - order-manager
          - post-trade
          - ml-signal-service
          - ui
        platform:
          - linux/amd64
          - linux/arm64
        include:
          - platform: linux/amd64
            runner: ubuntu-22.04
          - platform: linux/arm64
            runner: ubuntu-22.04-arm
    runs-on: ${{ matrix.runner }}
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build and push by digest
        id: build
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.service }}/Dockerfile
          platforms: ${{ matrix.platform }}
          outputs: type=image,name=ghcr.io/drag0sd0g/mariaalpha/${{ matrix.service }},push-by-digest=true,name-canonical=true,push=true
      - name: Export digest
        run: |
          mkdir -p /tmp/digests
          echo "${{ steps.build.outputs.digest }}" > /tmp/digests/${{ matrix.service }}-${{ matrix.platform == 'linux/amd64' && 'amd64' || 'arm64' }}
      - uses: actions/upload-artifact@v4
        with:
          name: digests-${{ matrix.service }}-${{ matrix.platform == 'linux/amd64' && 'amd64' || 'arm64' }}
          path: /tmp/digests/*

  merge-manifests:
    needs: build-and-push
    runs-on: ubuntu-22.04
    strategy:
      matrix:
        service:
          - api-gateway
          - market-data-gateway
          - strategy-engine
          - execution-engine
          - order-manager
          - post-trade
          - ml-signal-service
          - ui
    steps:
      - uses: actions/download-artifact@v4
        with:
          pattern: digests-${{ matrix.service }}-*
          path: /tmp/digests
          merge-multiple: true
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Create manifest list
        run: |
          SHA_SHORT=$(echo ${{ github.sha }} | cut -c1-7)
          docker buildx imagetools create \
            -t ghcr.io/drag0sd0g/mariaalpha/${{ matrix.service }}:sha-$SHA_SHORT \
            -t ghcr.io/drag0sd0g/mariaalpha/${{ matrix.service }}:latest \
            $(cat /tmp/digests/* | xargs -I {} echo ghcr.io/drag0sd0g/mariaalpha/${{ matrix.service }}@{})
```

Output: `ghcr.io/drag0sd0g/mariaalpha/<service>:sha-abc1234` and `:latest`, manifest-listed for both `linux/amd64` and `linux/arm64`. The OKE node (ARM64) automatically pulls the arm64 variant.

### 7.3 `deploy.yml` — OKE rollout

```yaml
name: Deploy to OKE
on:
  workflow_run:
    workflows: ["Docker Publish"]
    branches: [main]
    types: [completed]

jobs:
  deploy:
    if: ${{ github.event.workflow_run.conclusion == 'success' }}
    runs-on: ubuntu-22.04
    steps:
      - uses: actions/checkout@v4

      - name: Set up OCI CLI
        uses: oracle-actions/configure-oci-cli@v1
        with:
          tenancy: ${{ secrets.OCI_TENANCY_OCID }}
          user: ${{ secrets.OCI_USER_OCID }}
          fingerprint: ${{ secrets.OCI_FINGERPRINT }}
          privateKey: ${{ secrets.OCI_PRIVATE_KEY }}
          region: eu-frankfurt-1

      - name: Get kubeconfig
        run: |
          oci ce cluster create-kubeconfig \
            --cluster-id ${{ secrets.OKE_CLUSTER_OCID }} \
            --file ~/.kube/config \
            --region eu-frankfurt-1 \
            --token-version 2.0.0

      - name: Helm upgrade
        run: |
          SHA_SHORT=$(echo ${{ github.event.workflow_run.head_sha }} | cut -c1-7)
          helm upgrade --install mariaalpha ./helm/mariaalpha \
            --namespace mariaalpha --create-namespace \
            --set global.image.tag=sha-$SHA_SHORT \
            --wait --timeout 10m \
            --atomic  # automatic rollback on any failure

      - name: Post-deploy smoke check
        run: |
          # Wait for ingress IP propagation
          for i in {1..30}; do
            LB_IP=$(kubectl -n mariaalpha-infra get svc ingress-nginx-controller \
              -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
            [ -n "$LB_IP" ] && break
            sleep 10
          done

          # Hit readiness endpoint via ingress
          curl -fsS https://api.$LB_IP.nip.io/actuator/health/readiness | \
            jq -e '.status == "UP"' || (kubectl -n mariaalpha logs -l app=api-gateway --tail=200 && exit 1)
```

`--atomic` flag on `helm upgrade` triggers automatic rollback if any pod fails to become Ready within 10 minutes. No manual rollback step needed.

### 7.4 Secrets in GitHub Actions

| Secret | Source | Used by |
| --- | --- | --- |
| `OCI_TENANCY_OCID` | OCI console (Identity → Tenancy details) | deploy.yml |
| `OCI_USER_OCID` | OCI console (Identity → Users) | deploy.yml |
| `OCI_FINGERPRINT` | OCI console (Users → API keys) | deploy.yml |
| `OCI_PRIVATE_KEY` | Generated locally, public uploaded to OCI | deploy.yml |
| `OKE_CLUSTER_OCID` | OCI console (Container Engine → Cluster) | deploy.yml |
| `GITHUB_TOKEN` | auto-provided | docker-publish.yml |

`GITHUB_TOKEN` automatically has `packages: write` for the same repo — no additional PAT needed for GHCR push.

### 7.5 What we are NOT building

- **PR preview environments** — would require namespace-per-PR; expensive on a single-node cluster. Skip.
- **Canary or blue/green deploys** — single-node makes both impossible. Helm's atomic rollback is the only safety net.
- **Image signing (cosign)** — nice to have, not required for demo.

---

## 8. Observability in cloud

Keep the existing local LGTM stack (Loki + Grafana + Tempo + Prometheus + Alloy) running in-cluster. No changes to instrumentation — services already export Prometheus metrics on `/actuator/prometheus` and OTLP traces to Alloy.

**Differences from local:**
- Retention windows tightened (Loki/Prometheus: 7 days, Tempo: 3 days) to fit storage budget
- Grafana exposed publicly at `grafana.<IP>.nip.io` with anonymous-disabled (admin login required)
- Admin password rotated via sealed-secret; default `admin/admin` blocked

**Alerts:** Defer Prometheus Alertmanager + email/Slack to a follow-up issue. For now, eyeballing Grafana is fine.

---

## 9. Security model

### 9.1 Attack surface

| Exposed endpoint | Auth | Justification |
| --- | --- | --- |
| `<IP>.nip.io` (UI) | None | Static React app; all dynamic operations require API key |
| `api.<IP>.nip.io/api/*` | `X-API-Key` header | Existing filter in api-gateway |
| `api.<IP>.nip.io/actuator/*` | Blocked at ingress | Use `nginx.ingress.kubernetes.io/configuration-snippet` to return 404 |
| `grafana.<IP>.nip.io` | Grafana admin login | Sealed-secret-controlled password |
| SSH to node | OCI SSH key (allowlisted source IP) | Restrict source CIDR in security list |
| `kubectl` (port 6443) | OKE kubeconfig token | Restrict source CIDR in security list |

### 9.2 What stays internal

- All inter-service traffic (Kafka, gRPC, HTTP) is via ClusterIP — no NodePort, no LB
- Postgres and Kafka are namespace-isolated by NetworkPolicy (only `mariaalpha` namespace can connect)
- Actuator endpoints are blocked at ingress; only api-gateway exposes them, only to `/actuator/health` is exempted (for ingress healthcheck)

### 9.3 Hardening checklist

- [ ] Disable Grafana anonymous access (current local-only config)
- [ ] Block Postgres external port via NetworkPolicy
- [ ] Block Kafka external port via NetworkPolicy
- [ ] Enable Cloud Guard (free) on the compartment
- [ ] Rotate sealed-secrets master key annually
- [ ] Enable image-pull-policy: Always so `:latest` floats forward

---

## 10. Cost monitoring

| Mechanism | What it does | Action on trigger |
| --- | --- | --- |
| OCI Budget at $1 | Email when any resource crosses $1 USD/month | Investigate which resource left Always-Free quota |
| Cloud Guard | Daily security + policy posture scan | Review violations weekly |
| Cost analysis dashboard | Per-resource cost breakdown | Review monthly |
| GitHub Actions usage page | Track CI minutes (free for public repos but useful) | None — public repo is free |

**Hidden cost traps to avoid:**
- Bringing up a second Ampere VM beyond the 4-OCPU/24-GB pooled limit → billed
- Block Volume snapshots above 50 GB → billed
- Reserved Public IPs above 2 → billed
- LB shape above 10 Mbps → billed
- Object Storage above 20 GB → billed
- Outbound egress above 10 TB/month → billed (we use <30 GB/month)
- ATP/ADW above 20 GB (if used as Postgres replacement) → billed

---

## 11. Runbooks (to be created in `docs/runbooks/`)

| Runbook | Purpose |
| --- | --- |
| `oracle-cloud-bootstrap.md` | Initial account + OKE setup; one-time |
| `cloud-smoke-test.md` | Adapted from `alpaca-smoke-test.md`; runs against `https://api.<IP>.nip.io` |
| `cloud-debug.md` | SSH into node, `kubectl logs`, `kubectl describe`, port-forward |
| `cloud-rollback.md` | Manual `helm rollback` procedure when `--atomic` failed |
| `cloud-restore-postgres.md` | Restore from CronJob backup or Block Volume snapshot |
| `cloud-rotate-secrets.md` | Re-seal a SealedSecret, push, redeploy |
| `cloud-scale-down.md` | Scale all to 0 replicas (for maintenance) |
| `cloud-tear-down.md` | Full teardown sequence if abandoning the deployment |

---

## 12. TDD changes required (proposed; not yet executed)

### 12.1 Remove NG-5

Current §2.4:
> | NG-5 | Cloud deployment — the MVP targets local Kubernetes only. |

**Action:** Delete entirely.

### 12.2 Add G-10

Insert in §2.3 Goals:
> | G-10 | Deploy the full stack to Oracle Cloud Always-Free (Ampere A1 + OKE) and keep it continuously deployed via GitHub Actions on every push to `main`. |

### 12.3 Rework §10 Deployment

- Rename §10.2 from "Kubernetes Deployment (Helm)" to "Local development sizing" — the table is still useful as a guide for laptop dev.
- Add §10.3 "Cloud topology" with the architecture diagram from §4 above.
- Add §10.4 "Cloud sizing" with the table from §5 above.
- Add §10.5 "CI/CD deploy pipeline" referencing the workflow specs in §7.
- Add §10.6 "Cloud secrets" referencing sealed-secrets in §6.8.
- Add §10.7 "Cloud runbooks" pointing to `docs/runbooks/oracle-cloud-*.md`.

### 12.4 Update §5.5 Technology Choices

Add a row to the table:
| Cloud platform | Oracle Cloud Infrastructure (Always-Free Ampere A1 + OKE) | Only provider in 2026 offering a free-forever 4 OCPU / 24 GB shape suitable for our 10-service stack. Frankfurt region selected for capacity availability and Alpaca latency. |

---

## 13. Phase 2 re-prioritization

### 13.1 New milestone: "2.8 Cloud Deployment" (inserted before resuming 2.1.5)

| # | New/Modified | Title | Description |
| --- | --- | --- | --- |
| (existing) #82 | Modify | [2.7.1] Create Helm charts for K8s deployment | Expand acceptance: chart works against single-node OKE; `values-cloud.yaml` override file for cloud vs local; resource limits match §5 above; namespace separation (app/data/o11y/infra) |
| (existing) #83 | Modify | [2.7.2] Implement Docker image publish workflow | Expand acceptance: multi-arch manifests (amd64 + arm64); native ARM runners (`ubuntu-22.04-arm`); GHCR push with `:sha-<7>` and `:latest`; build-time <8 min |
| 2.8.1 | New | Provision Oracle OKE cluster and VCN | Terraform module creates VCN, 1 Ampere A1 worker node (4 OCPU / 24 GB), free flexible LB, 2 reserved IPs, security lists per §6.2. Documented retry strategy for Ampere capacity (hitrov script). |
| 2.8.2 | New | Set up ingress, DNS, and TLS | NGINX Ingress Controller via Helm; cert-manager + Let's Encrypt ClusterIssuer; nip.io domain mapping. Smoke: `curl https://<IP>.nip.io` returns UI with valid cert. |
| 2.8.3 | New | Implement sealed-secrets for cloud secrets management | Install controller; document `kubeseal` workflow; convert all `.env` keys to SealedSecret resources committed to the Helm chart. |
| 2.8.4 | New | Configure persistent storage and backups | PVCs for PostgreSQL (20 GB), Kafka (20 GB), Prometheus (10 GB), Loki (10 GB), Tempo (5 GB), Grafana (1 GB) bind to `oci-bv` storage class. CronJob for daily pg_dump with 7-day rotation. |
| 2.8.5 | New | Implement `deploy.yml` workflow | GitHub Actions workflow auths to OKE via OCI key, runs `helm upgrade --atomic`. Post-deploy smoke test green-gates the workflow. |
| 2.8.6 | New | Cloud security hardening | Block actuator endpoints at ingress, enable NetworkPolicies for stateful namespace, rotate Grafana admin password, enable Cloud Guard. |
| 2.8.7 | New | Cloud smoke test runbook + observability check | Adapted from `docs/runbooks/alpaca-smoke-test.md` — runs against `https://api.<IP>.nip.io`. Verify Grafana dashboards load with cloud data. |

**Estimated effort:** 1.5–2 weeks of focused work for a single engineer, broken down:
- 2.8.1 (cluster provisioning): 2–3 days (variable based on Ampere capacity wait)
- 2.8.2 (ingress/DNS/TLS): 1 day
- 2.8.3 (sealed-secrets): 0.5 day
- 2.8.4 (storage + backups): 1 day
- #82 expansion (Helm charts): 2 days
- #83 expansion (multi-arch CI): 1 day
- 2.8.5 (deploy workflow): 1 day
- 2.8.6 (security hardening): 1 day
- 2.8.7 (smoke runbook): 0.5 day

### 13.2 Existing issues to defer or pull forward

| Action | Issue | Reason |
| --- | --- | --- |
| Pull forward | #82, #83 | Now blocking 2.8.x rather than parking at the end of Phase 2 |
| Keep where they are | #84 (mutation testing) | Independent of cloud — finish after cloud milestone |
| Keep where they are | #85 (Redis) | Adds memory pressure on free tier; defer indefinitely until needed |
| Keep where they are | #86 (Bruno API collection) | Independent of cloud |
| Modify | #110 [4.5.1] Cloud IaC (Terraform) | Original "GCP/AWS multi-cloud" scope is obsolete; rewrite as "Phase 4 multi-region HA expansion of OKE deployment" |

### 13.3 Suggested Phase 2 order (post-cloud milestone)

The rest of Phase 2 doesn't strictly need reordering. Original order is fine:
- 2.1.5 (TWAP) → 2.1.6 (Momentum) → 2.1.7 (IS) → 2.1.8 (POV) → 2.1.9 (Close) → 2.1.10 (Internalization)
- Then 2.2.x risk checks
- Then 2.3.x ML
- Then 2.4.x RFQ pricing
- Then 2.5.x UI pages
- Then 2.6.x reconciliation + dashboards
- Then 2.7.3, 2.7.4, 2.7.5 leftovers (mutation testing, Redis, Bruno)

Every PR from 2.1.5 onward will auto-deploy to cloud, so the cloud serves as a continuous integration target.

---

## 14. Risks and mitigations

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Ampere A1 capacity unavailable indefinitely | Medium | Frankfurt region + hitrov retry script. Worst case: park cloud milestone, retry weekly. |
| Free LB shape (10 Mbps) throttles during load tests | Low | Paper trading is low-bandwidth (<1 Mbps actual). Document and proceed. |
| Single-node cluster has no HA; node reboot = full outage | Low | Acceptable for a demo. Outages are infrequent (<1/quarter historically). Monitoring alert via Grafana when desired. |
| PostgreSQL data loss on node loss | Medium | Daily pg_dump CronJob + weekly Block Volume snapshot. RTO ~30 min, RPO ~24h. Acceptable for paper trading demo. |
| Free tier accounts reclaimed by Oracle if "inactive" | Low | Active running compute instance prevents idle reclamation. Plus: log in to console monthly. |
| Alpaca paper API rate-limits or geo-blocks from Frankfurt | Low | Verified: Alpaca paper has no geo-blocks as of 2026. Latency ~85 ms tolerable. |
| Multi-arch Docker builds add 5–8 min to CI | Low | Native ARM runners (`ubuntu-22.04-arm`) avoid QEMU emulation. Per-arch cache hits typical. |
| `latest` tag race condition (deploy pulls older image than CI just pushed) | Low | We use `:sha-<7>` not `:latest` in deploy.yml. `:latest` is for ad-hoc `kubectl rollout restart`. |
| Costs creep above free tier accidentally (extra IPs, snapshots) | Medium | OCI budget alert at $1. Daily Cloud Guard scan. Monthly cost analysis review (calendar reminder). |
| Let's Encrypt rate limits hit during repeated deploys | Low | Production endpoint allows 50 certs/week per domain. Use staging endpoint for testing. |
| Domain (nip.io) goes offline | Low | `sslip.io` is a drop-in alternative. Documented in 2.8.7 runbook. |
| Ampere CPU performance differs from x86 dev machines (Spring Boot startup ~30% slower) | Low | Startup probe timeouts already set generously in TDD. Verified during 2.8.7 smoke test. |
| `helm upgrade --atomic` rollback gets stuck if PV is mid-resize | Medium | Don't resize PVs in deploy.yml. Manual `helm rollback` runbook (2.8.6) for stuck cases. |
| OCI Vault free tier limits (20 keys, 150 secrets) | Low | Using sealed-secrets, not Vault. No exposure. |

---

## 15. Acceptance criteria

The cloud deployment milestone is complete when:

1. **Continuous deploy works:** Pushing to `main` triggers a successful build → publish → deploy chain in <15 minutes end-to-end.
2. **Public endpoints respond:**
   - `https://<IP>.nip.io` serves the UI with a valid Let's Encrypt certificate
   - `https://api.<IP>.nip.io/actuator/health/readiness` returns `{"status":"UP"}` with all required downstreams UP (api-gateway has internal endpoint exemption)
3. **Smoke test passes against cloud:** Cloud-adapted version of `alpaca-smoke-test.md` passes Steps 1–9 end-to-end against the cloud stack during US market hours.
4. **Rollback is operational:** A deliberate `helm upgrade` with a broken image triggers `--atomic` rollback in <2 minutes. Manual `helm rollback` runbook is tested.
5. **Backups are testable:** Restoring from yesterday's pg_dump completes in <30 minutes. Block Volume snapshot restore verified.
6. **Observability is end-to-end:** Grafana shows live data from cloud-deployed services. Logs from all pods are searchable in Loki. Traces visible in Tempo.
7. **Secrets are git-tracked encrypted:** All SealedSecret resources committed to `helm/mariaalpha/templates/`. No plaintext secrets in git.
8. **Cost is verified $0:** Monthly Oracle bill is $0 for at least one full billing cycle.
9. **TDD updated:** NG-5 removed, G-10 added, §10 expanded with cloud topology and runbooks.
10. **Runbooks complete:** All 8 runbooks in `docs/runbooks/cloud-*.md` written and at least one independent dry-run performed per runbook.

---

## 16. Open questions

| Question | Default if not answered | Who decides |
| --- | --- | --- |
| Use OCI Vault or sealed-secrets? | sealed-secrets (simpler) | User |
| nip.io vs purchased domain? | nip.io (free) | User |
| Restrict SSH/kubectl to specific IP, or open? | Open with kubectl auth only; SSH disabled after bootstrap | User |
| Enable Grafana anonymous read-only? | Disable (admin login required) | User |
| Enable Tempo (traces) in cloud? | Yes — fits storage budget | Inherited from TDD |
| Add Alertmanager + email alerts? | No — deferred to a follow-up issue | User |
| Use OKE Basic (free control plane) or self-managed k3s on the Ampere VM? | OKE Basic (less operational burden, free anyway) | User |
| Region: Frankfurt or Singapore? | Frankfurt (better Alpaca latency for a US-based dev) | User |
| Keep TDD §10.2 local sizing table? | Yes — rename to "Local development sizing" | User |
| Will I demo this to people? If so, may want a real domain for memorability | nip.io | User |

---

## 17. Appendices

### Appendix A — Terraform skeleton for 2.8.1

```hcl
# terraform/main.tf
terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 5.0"
    }
  }
}

provider "oci" {
  tenancy_ocid     = var.tenancy_ocid
  user_ocid        = var.user_ocid
  fingerprint      = var.fingerprint
  private_key_path = var.private_key_path
  region           = "eu-frankfurt-1"
}

# VCN
resource "oci_core_vcn" "mariaalpha" {
  compartment_id = var.compartment_ocid
  display_name   = "mariaalpha-vcn"
  cidr_blocks    = ["10.0.0.0/16"]
  dns_label      = "mariaalpha"
}

resource "oci_core_internet_gateway" "ig" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.mariaalpha.id
  display_name   = "mariaalpha-ig"
}

resource "oci_core_subnet" "public" {
  compartment_id  = var.compartment_ocid
  vcn_id          = oci_core_vcn.mariaalpha.id
  cidr_block      = "10.0.1.0/24"
  display_name    = "mariaalpha-public"
  prohibit_public_ip_on_vnic = false
}

# OKE cluster (Basic — free control plane)
resource "oci_containerengine_cluster" "mariaalpha" {
  compartment_id     = var.compartment_ocid
  kubernetes_version = "v1.30.1"
  name               = "mariaalpha-prod"
  vcn_id             = oci_core_vcn.mariaalpha.id
  type               = "BASIC_CLUSTER"

  endpoint_config {
    is_public_ip_enabled = true
    subnet_id            = oci_core_subnet.public.id
  }
}

# Ampere A1 worker pool
resource "oci_containerengine_node_pool" "ampere" {
  cluster_id         = oci_containerengine_cluster.mariaalpha.id
  compartment_id     = var.compartment_ocid
  kubernetes_version = "v1.30.1"
  name               = "ampere-pool"

  node_shape = "VM.Standard.A1.Flex"
  node_shape_config {
    ocpus         = 4
    memory_in_gbs = 24
  }

  node_config_details {
    placement_configs {
      availability_domain = data.oci_identity_availability_domain.ad.name
      subnet_id           = oci_core_subnet.public.id
    }
    size = 1
  }

  node_source_details {
    image_id    = data.oci_core_images.ampere_oel.images[0].id
    source_type = "IMAGE"
    boot_volume_size_in_gbs = 50
  }
}
```

### Appendix B — Helm chart structure for 2.8.x

```
helm/mariaalpha/
├── Chart.yaml
├── values.yaml                  # default (local-dev compatible)
├── values-cloud.yaml            # cloud overrides (replicas=1, resources match §5)
├── templates/
│   ├── _helpers.tpl
│   ├── namespace.yaml
│   ├── secrets-sealed.yaml      # sealed secrets for app/db/grafana
│   ├── postgres/
│   │   ├── statefulset.yaml
│   │   ├── service.yaml
│   │   ├── pvc.yaml
│   │   └── backup-cronjob.yaml
│   ├── kafka/
│   │   ├── statefulset.yaml
│   │   └── service.yaml
│   ├── apps/
│   │   ├── api-gateway-deployment.yaml
│   │   ├── api-gateway-service.yaml
│   │   ├── api-gateway-ingress.yaml
│   │   ├── ui-deployment.yaml
│   │   ├── ui-service.yaml
│   │   ├── ui-ingress.yaml
│   │   ├── market-data-gateway-*.yaml
│   │   ├── strategy-engine-*.yaml
│   │   ├── execution-engine-*.yaml
│   │   ├── order-manager-*.yaml
│   │   ├── post-trade-*.yaml
│   │   └── ml-signal-service-*.yaml
│   ├── observability/
│   │   ├── prometheus-*.yaml
│   │   ├── loki-*.yaml
│   │   ├── tempo-*.yaml
│   │   ├── alloy-daemonset.yaml
│   │   └── grafana-*.yaml
│   └── infrastructure/
│       ├── cert-manager-issuer.yaml
│       └── network-policies.yaml
```

Each service deployment is parameterised by `.Values.global.image.tag` so deploy.yml's `--set global.image.tag=sha-abc1234` updates every service in one shot.

### Appendix C — Ingress resource example

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-gateway
  namespace: mariaalpha
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/configuration-snippet: |
      location ~* "^/actuator" {
        return 404;
      }
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - api.{{ .Values.global.lbIp }}.nip.io
      secretName: api-gateway-tls
  rules:
    - host: api.{{ .Values.global.lbIp }}.nip.io
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 8080
```

### Appendix D — Bootstrap commands (one-time, after Terraform apply)

```bash
# 1. Get kubeconfig
oci ce cluster create-kubeconfig --cluster-id $OKE_CLUSTER_OCID \
  --file ~/.kube/config --region eu-frankfurt-1 --token-version 2.0.0

# 2. Install ingress-nginx
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace mariaalpha-infra --create-namespace \
  --set controller.service.annotations."service\.beta\.kubernetes\.io/oci-load-balancer-shape"=flexible \
  --set controller.service.annotations."service\.beta\.kubernetes\.io/oci-load-balancer-shape-flex-min"=10 \
  --set controller.service.annotations."service\.beta\.kubernetes\.io/oci-load-balancer-shape-flex-max"=10

# 3. Install cert-manager
helm repo add jetstack https://charts.jetstack.io
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace \
  --set installCRDs=true

# 4. Apply Let's Encrypt ClusterIssuer
kubectl apply -f infra/letsencrypt-issuer.yaml

# 5. Install sealed-secrets controller
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets \
  --namespace kube-system

# 6. Fetch the LB IP (assigned by OCI) and bake into values-cloud.yaml
LB_IP=$(kubectl -n mariaalpha-infra get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
yq -i ".global.lbIp = \"$LB_IP\"" helm/mariaalpha/values-cloud.yaml

# 7. Seal initial secrets
./scripts/seal-secrets.sh > helm/mariaalpha/templates/secrets-sealed.yaml

# 8. First deploy
helm install mariaalpha ./helm/mariaalpha -f helm/mariaalpha/values-cloud.yaml \
  --namespace mariaalpha --create-namespace --wait --timeout 15m

# 9. Confirm
curl -fsS https://api.$LB_IP.nip.io/actuator/health/readiness
```

---

## 18. Next concrete step

Once you approve this document, the execution sequence is:

1. ~~**Finish 2.1.3 + 2.1.4** on the current branch and open a PR. Merge.~~ ✅ Done (commits `87cbb54`, `cb96a14`).
2. **Sign up for Oracle Cloud** with home region Frankfurt. Run `hitrov` script in the background to acquire Ampere capacity (no impact on Step 3 — runs in parallel).
3. **Create the 8 new issues** listed in §13.1 (2.8.1 – 2.8.7) and expand the acceptance criteria on #82 and #83.
4. **Edit the TDD** (NG-5 → G-10, §10 expansion) as a separate PR for clean history.
5. **Begin work on 2.8.1** once the Ampere node provisions successfully.
6. **Each subsequent 2.8.x issue** lands as its own PR; together they unblock the continuous deploy of Phase 2 features starting at 2.1.5.

If at step 2 we cannot acquire Ampere capacity within 2 weeks despite the retry script:
- Park the cloud milestone
- Continue Phase 2 feature work locally (no deploy step gating)
- Retry capacity weekly
- Cloud deploy ships when capacity is acquired, retroactively bringing all merged commits into the cloud

This is the only honest path that holds the "$0/month, no time limit" constraint.
