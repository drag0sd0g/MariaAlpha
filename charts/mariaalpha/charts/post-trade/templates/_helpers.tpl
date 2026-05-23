{{- define "post-trade.image" -}}
{{- $registry := .Values.global.images.registry | default "" -}}
{{- $repo    := .Values.global.images.repository | default "mariaalpha" -}}
{{- $tag     := (default .Values.global.images.tag .Values.image.tag) -}}
{{- if $registry -}}
{{ $registry }}/{{ $repo }}/post-trade:{{ $tag }}
{{- else -}}
{{ $repo }}/post-trade:{{ $tag }}
{{- end -}}
{{- end -}}

{{- define "post-trade.labels" -}}
app.kubernetes.io/name: post-trade
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: mariaalpha
app.kubernetes.io/component: post-trade
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "post-trade.selectorLabels" -}}
app.kubernetes.io/name: post-trade
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
