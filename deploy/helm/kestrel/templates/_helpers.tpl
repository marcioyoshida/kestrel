{{- define "kestrel.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "kestrel.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "kestrel.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/part-of: kestrel
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "kestrel.web.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kestrel.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: web
{{- end -}}

{{- define "kestrel.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "kestrel.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- required "serviceAccount.name is required when serviceAccount.create=false" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "kestrel.image" -}}
{{- $repo := .Values.image.repository -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $repo .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (required "image.tag (or image.digest) is required" .Values.image.tag) -}}
{{- end -}}
{{- end -}}

{{/*
Install-time guards. Fail loudly instead of rendering a deployment that looks healthy but
misbehaves (double-firing schedules, logs that never reach S3, random login redirects).
*/}}
{{- define "kestrel.validate" -}}
{{- if and (eq .Values.scheduler.mode "quartz") (gt (int .Values.web.replicas) 1) -}}
{{- fail "web.replicas > 1 requires scheduler.mode=kubernetes (M1). With Quartz in-JVM, OSS has no schedule takeover: replicas double-fire or orphan jobs." -}}
{{- end -}}
{{- if ne .Values.scheduler.mode "quartz" -}}
{{- fail (printf "scheduler.mode=%s is not implemented yet (M1); only 'quartz' is available" .Values.scheduler.mode) -}}
{{- end -}}
{{- if .Values.logStorage.s3.enabled -}}
{{- $has := false -}}
{{- range .Values.plugins -}}{{- if eq (default "" .provides) "org.rundeck.amazon-s3" -}}{{- $has = true -}}{{- end -}}{{- end -}}
{{- if not $has -}}
{{- fail "logStorage.s3.enabled=true but no plugin with provides=org.rundeck.amazon-s3 is listed in .Values.plugins (it is not bundled in the upstream image)" -}}
{{- end -}}
{{- end -}}
{{- range .Values.plugins -}}
{{- if not (regexMatch "^[a-f0-9]{64}$" (default "" .sha256)) -}}
{{- fail (printf "plugin %s: sha256 must be a 64-char lowercase hex digest" .name) -}}
{{- end -}}
{{- if not (regexMatch "^[A-Za-z0-9._-]+\\.jar$" (default "" .name)) -}}
{{- fail (printf "plugin name %q must be a plain .jar filename" .name) -}}
{{- end -}}
{{- end -}}
{{- end -}}
