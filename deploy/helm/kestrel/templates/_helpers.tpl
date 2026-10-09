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
{{- if .Values.auth.enabled -}}
{{- if not (has .Values.auth.provider (list "cognito" "oidc")) -}}
{{- fail (printf "auth.provider must be cognito or oidc, got %q" .Values.auth.provider) -}}
{{- end -}}
{{- if not (hasPrefix "https://" (default "" .Values.auth.issuerUrl)) -}}
{{- fail "auth.issuerUrl must be an https:// OIDC issuer" -}}
{{- end -}}
{{- if not .Values.auth.existingSecret -}}
{{- fail "auth.existingSecret is required (keys: client-id, client-secret, cookie-secret)" -}}
{{- end -}}
{{- end -}}
{{- if and .Values.targetGroupBinding.enabled .Values.ingress.enabled -}}
{{- fail "targetGroupBinding.enabled and ingress.enabled are exclusive: the TGB attaches pods to an ALB owned by infra, the Ingress would create a second one" -}}
{{- end -}}
{{- if and .Values.targetGroupBinding.enabled (not (hasPrefix "arn:aws:elasticloadbalancing:" (default "" .Values.targetGroupBinding.targetGroupArn))) -}}
{{- fail "targetGroupBinding.targetGroupArn must be an ELBv2 target group ARN" -}}
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

{{/* Probe for the Rundeck container: httpGet when it listens on the pod IP, exec on loopback. */}}
{{- define "kestrel.web.probe" -}}
{{- $ := index . 0 -}}{{- $path := index . 1 -}}
{{- if $.Values.auth.enabled -}}
exec:
  command: ["curl", "-fsS", "-o", "/dev/null", "--max-time", "4", "http://127.0.0.1:4440{{ $path }}"]
{{- else -}}
httpGet: { path: {{ $path }}, port: http }
{{- end -}}
{{- end -}}

{{/*
oauth2-proxy sidecar (ADR 0001 §6). It is the only listener on the pod IP. It strips any
client-supplied X-Forwarded-User/Email/Groups/Preferred-Username (also on skip-auth routes) and
sets them from the verified ID token; Rundeck preauth trusts nothing else.
Skip-auth routes: the API (Rundeck token auth), public static assets, and the health endpoints
the ALB target group checks (/monitoring is blocked at the ALB and CloudFront for viewers).
*/}}
{{- define "kestrel.auth.container" -}}
{{- $a := .Values.auth -}}
{{- $groups := default (ternary "cognito:groups" "groups" (eq $a.provider "cognito")) $a.groupsClaim -}}
- name: auth-proxy
  image: {{ $a.image }}
  args:
    - --http-address=0.0.0.0:4180
    - --upstream=http://127.0.0.1:4440/
    - --provider=oidc
    - --provider-display-name={{ ternary "Cognito" "SSO" (eq $a.provider "cognito") }}
    - --oidc-issuer-url={{ $a.issuerUrl }}
    - --oidc-groups-claim={{ $groups }}
    - --oidc-email-claim=email
    - --scope=openid email profile
    - --code-challenge-method=S256
    - --redirect-url={{ trimSuffix "/" .Values.publicUrl }}/oauth2/callback
    - --email-domain=*
    {{- range $a.allowedGroups }}
    - --allowed-group={{ . }}
    {{- end }}
    - --skip-provider-button=true
    - --pass-user-headers=true
    - --pass-access-token=false
    - --skip-auth-strip-headers=true
    - --skip-auth-route=^/api/
    - --skip-auth-route=GET=^/(assets|static)/
    - --skip-auth-route=GET=^/monitoring/health(/readiness|/liveness)?$
    - --ping-path=/oauth2/ping
    - --silence-ping-logging=true
    - --cookie-name=_kestrel_auth
    - --cookie-secure=true
    - --cookie-samesite=lax
    - --cookie-expire={{ .Values.web.sessionTimeoutSeconds }}s
    - --cookie-refresh=50m
    - --upstream-timeout=120s
  env:
    - name: OAUTH2_PROXY_CLIENT_ID
      valueFrom: { secretKeyRef: { name: {{ $a.existingSecret }}, key: client-id } }
    - name: OAUTH2_PROXY_CLIENT_SECRET
      valueFrom: { secretKeyRef: { name: {{ $a.existingSecret }}, key: client-secret } }
    - name: OAUTH2_PROXY_COOKIE_SECRET
      valueFrom: { secretKeyRef: { name: {{ $a.existingSecret }}, key: cookie-secret } }
  ports:
    - name: http
      containerPort: 4180
  readinessProbe:
    httpGet: { path: /oauth2/ping, port: http }
    periodSeconds: 10
  livenessProbe:
    httpGet: { path: /oauth2/ping, port: http }
    periodSeconds: 20
    failureThreshold: 3
  securityContext:
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    runAsNonRoot: true
    runAsUser: 65532
    capabilities: { drop: ["ALL"] }
  resources:
    {{- toYaml $a.resources | nindent 4 }}
{{- end -}}
