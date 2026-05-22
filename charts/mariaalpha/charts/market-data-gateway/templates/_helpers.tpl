{{- define "market-data-gateway.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/market-data-gateway:{{ $tag }}
{{- else -}}
{{ $repo }}/market-data-gateway:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "market-data-gateway.labels" -}}
app.kubernetes.io/name: market-data-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: market-data-gateway
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "market-data-gateway.selectorLabels" -}}
app.kubernetes.io/name: market-data-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
