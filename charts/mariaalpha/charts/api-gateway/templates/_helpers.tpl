{{- define "api-gateway.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/api-gateway:{{ $tag }}
{{- else -}}
{{ $repo }}/api-gateway:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "api-gateway.labels" -}}
app.kubernetes.io/name: api-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: api-gateway
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "api-gateway.selectorLabels" -}}
app.kubernetes.io/name: api-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
