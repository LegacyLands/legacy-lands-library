{{/*
Expand the name of the chart.
*/}}
{{- define "task-scheduler.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "task-scheduler.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "task-scheduler.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "task-scheduler.labels" -}}
helm.sh/chart: {{ include "task-scheduler.chart" . }}
{{ include "task-scheduler.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "task-scheduler.selectorLabels" -}}
app.kubernetes.io/name: {{ include "task-scheduler.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "task-scheduler.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "task-scheduler.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Get the namespace
*/}}
{{- define "task-scheduler.namespace" -}}
{{- if .Values.namespace.name }}
{{- .Values.namespace.name }}
{{- else }}
{{- .Release.Namespace }}
{{- end }}
{{- end }}

{{/*
Get NATS URL
*/}}
{{- define "task-scheduler.natsUrl" -}}
{{- if .Values.nats.enabled }}
nats://{{ .Release.Name }}-nats.{{ include "task-scheduler.namespace" . }}.svc.cluster.local:4222
{{- else }}
{{- .Values.nats.externalUrl | default "nats://localhost:4222" }}
{{- end }}
{{- end }}

{{/*
Get the image registry
*/}}
{{- define "task-scheduler.imageRegistry" -}}
{{- if .Values.global.imageRegistry }}
{{- .Values.global.imageRegistry }}
{{- else }}
{{- "" }}
{{- end }}
{{- end }}

{{/*
Get the image for a component
*/}}
{{- define "task-scheduler.image" -}}
{{- $registry := include "task-scheduler.imageRegistry" . }}
{{- if $registry }}
{{- printf "%s/%s:%s" $registry .repository .tag }}
{{- else }}
{{- printf "%s:%s" .repository .tag }}
{{- end }}
{{- end }}

{{/*
Return the appropriate apiVersion for HPA
*/}}
{{- define "task-scheduler.hpa.apiVersion" -}}
{{- if .Capabilities.APIVersions.Has "autoscaling/v2" -}}
{{- print "autoscaling/v2" -}}
{{- else -}}
{{- print "autoscaling/v2beta2" -}}
{{- end -}}
{{- end -}}

{{/*
Create environment variables for observability
*/}}
{{- define "task-scheduler.observabilityEnvVars" -}}
- name: RUST_LOG
  value: "info"
- name: LOG_LEVEL
  value: "info"
- name: TRACING_ENABLED
  value: {{ .Values.observability.tracing.enabled | quote }}
- name: OTLP_ENDPOINT
  value: {{ .Values.observability.tracing.endpoint | default .Values.observability.tracing.otlpEndpoint | default "" | quote }}
{{- end }}

{{/*
Create volume mounts for plugins
*/}}
{{- define "task-scheduler.pluginVolumeMounts" -}}
{{- range $dir := .Values.taskWorker.config.plugins.dirs }}
- name: plugins-{{ $dir | replace "/" "-" | trimPrefix "-" }}
  mountPath: {{ $dir }}
  readOnly: true
{{- end }}
{{- end }}

{{/*
Create volumes for plugins
*/}}
{{- define "task-scheduler.pluginVolumes" -}}
{{- range $dir := .Values.taskWorker.config.plugins.dirs }}
- name: plugins-{{ $dir | replace "/" "-" | trimPrefix "-" }}
  emptyDir: {}
{{- end }}
{{- end }}