# Runbook: Rotate secrets in the MariaAlpha Helm chart

Two flavours depending on whether the chart is running in `plain` mode (the default for local) or `sealed-secrets` mode (production).

## A. Plain mode (`secrets.useSealedSecrets: false`)

Override the value on upgrade and roll the deployments that consume it.

```bash
NEW_KEY=$(openssl rand -hex 24)

helm upgrade mariaalpha charts/mariaalpha -n mariaalpha \
  --reuse-values \
  --set secrets.mariaalphaApiKey="${NEW_KEY}" \
  --wait

# UI's init container re-renders /config.js from the Secret on each pod start.
kubectl -n mariaalpha rollout restart deployment/api-gateway deployment/ui

# Hand the new key to whoever needs it (1Password, a teammate, etc.).
echo "$NEW_KEY"
```

Notes:
- API key changes do **not** require an image rebuild — the UI init container reads the Secret at pod startup and writes `/config.js`.
- Postgres password changes require a coordinated restart of every service that mounts `postgres-app-credentials`. Easier: tear down with `just k8s-down` and reinstall.

## B. Sealed-secrets mode (`secrets.useSealedSecrets: true`)

Pre-requisites: sealed-secrets controller installed (`sealed-secrets.enabled: true` in values) and `kubeseal` on the laptop.

### One-time per cluster: fetch the public cert

```bash
kubeseal --controller-namespace mariaalpha-infra --fetch-cert \
  > charts/mariaalpha/sealed-secrets/_cert.pem
```

The cert can be committed to git — it is the *public* half. Only the private key inside the cluster can decrypt sealed secrets.

### Re-seal a secret

```bash
# 1. Author the cleartext Secret (DO NOT COMMIT):
cat > /tmp/mariaalpha-api-key.yaml <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: mariaalpha-api-key
  namespace: mariaalpha
type: Opaque
stringData:
  MARIAALPHA_API_KEY: $(openssl rand -hex 24)
EOF

# 2. Seal it:
kubeseal --controller-namespace mariaalpha-infra \
  --cert charts/mariaalpha/sealed-secrets/_cert.pem \
  --format yaml \
  < /tmp/mariaalpha-api-key.yaml \
  > charts/mariaalpha/sealed-secrets/mariaalpha-api-key.yaml

# 3. Discard the cleartext, commit the sealed manifest:
rm /tmp/mariaalpha-api-key.yaml
git add charts/mariaalpha/sealed-secrets/mariaalpha-api-key.yaml
git commit -m "chore: rotate MARIAALPHA_API_KEY"
```

### Roll out

```bash
helm upgrade mariaalpha charts/mariaalpha -n mariaalpha --wait
kubectl -n mariaalpha rollout restart deployment/api-gateway deployment/ui
```

The sealed-secrets controller decrypts the new `SealedSecret` and overwrites the live `Secret`; the pods pick up the new value when they restart.

### Disaster recovery — losing the controller's private key

If the `sealed-secrets-key*` Secret in `mariaalpha-infra` is lost, **all sealed manifests in git become undecryptable**. There is no recovery; re-seal every secret against the new cert.

Mitigation:

```bash
kubectl -n mariaalpha-infra get secret -l sealedsecrets.bitnami.com/sealed-secrets-key \
  -o yaml > /secure-storage/sealed-secrets-master-key.yaml
```

Keep this file in 1Password or an offline encrypted store.
