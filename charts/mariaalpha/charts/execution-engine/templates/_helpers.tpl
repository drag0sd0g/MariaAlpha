{{/*
Resolve the full image string. Global values are injected by the umbrella chart
into every subchart, so `.Values.global.images.*` is available here.
*/}}
{{- define "execution-engine.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/execution-engine:{{ $tag }}
{{- else -}}
{{ $repo }}/execution-engine:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "execution-engine.labels" -}}
app.kubernetes.io/name: execution-engine
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: execution-engine
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "execution-engine.selectorLabels" -}}
app.kubernetes.io/name: execution-engine
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
