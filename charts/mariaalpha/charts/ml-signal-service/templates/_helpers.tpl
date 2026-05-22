{{- define "ml-signal-service.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/ml-signal-service:{{ $tag }}
{{- else -}}
{{ $repo }}/ml-signal-service:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "ml-signal-service.labels" -}}
app.kubernetes.io/name: ml-signal-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: ml-signal-service
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "ml-signal-service.selectorLabels" -}}
app.kubernetes.io/name: ml-signal-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
