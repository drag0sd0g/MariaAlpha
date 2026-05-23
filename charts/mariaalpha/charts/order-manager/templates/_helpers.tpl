{{- define "order-manager.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/order-manager:{{ $tag }}
{{- else -}}
{{ $repo }}/order-manager:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "order-manager.labels" -}}
app.kubernetes.io/name: order-manager
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: order-manager
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "order-manager.selectorLabels" -}}
app.kubernetes.io/name: order-manager
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
