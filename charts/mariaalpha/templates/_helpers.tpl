{{/*
Common labels applied to every umbrella-rendered resource.
*/}}
{{- define "mariaalpha.commonLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mariaalpha
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
Resolve the application namespace from globals.
*/}}
{{- define "mariaalpha.appNamespace" -}}
{{ .Values.global.appNamespace | default "mariaalpha" }}
{{- end -}}

{{- define "mariaalpha.dataNamespace" -}}
{{ .Values.global.dataNamespace | default "mariaalpha-data" }}
{{- end -}}

{{- define "mariaalpha.o11yNamespace" -}}
{{ .Values.global.appNamespace | default "mariaalpha" }}
{{- end -}}
