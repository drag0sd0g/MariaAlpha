{{- define "strategy-engine.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/strategy-engine:{{ $tag }}
{{- else -}}
{{ $repo }}/strategy-engine:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "strategy-engine.labels" -}}
app.kubernetes.io/name: strategy-engine
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: strategy-engine
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "strategy-engine.selectorLabels" -}}
app.kubernetes.io/name: strategy-engine
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
