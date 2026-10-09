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
{{- if not (has .Values.scheduler.mode (list "quartz" "kubernetes")) -}}
{{- fail (printf "scheduler.mode must be quartz or kubernetes, got %q" .Values.scheduler.mode) -}}
{{- end -}}
{{- if and (eq .Values.scheduler.mode "quartz") (gt (int .Values.web.replicas) 1) -}}
{{- fail "web.replicas > 1 requires scheduler.mode=kubernetes. With Quartz in-JVM, OSS has no schedule takeover: replicas double-fire or orphan jobs." -}}
{{- end -}}
{{- if eq .Values.scheduler.mode "kubernetes" -}}
{{- if not (hasPrefix "https://sqs." (default "" .Values.scheduler.queueUrl)) -}}
{{- fail "scheduler.queueUrl must be the SQS FIFO fire queue URL (https://sqs.<region>.amazonaws.com/...fifo)" -}}
{{- end -}}
{{- if not (hasSuffix ".fifo" .Values.scheduler.queueUrl) -}}
{{- fail "scheduler.queueUrl must be a FIFO queue (.fifo): deduplication of retried triggers depends on it" -}}
{{- end -}}
{{- if not .Values.scheduler.ledgerTable -}}
{{- fail "scheduler.ledgerTable (DynamoDB fire ledger) is required in kubernetes mode" -}}
{{- end -}}
{{- if not .Values.scheduler.trigger.image -}}
{{- fail "scheduler.trigger.image is required in kubernetes mode" -}}
{{- end -}}
{{- if lt (int .Values.runner.replicas) 1 -}}
{{- fail "runner.replicas must be at least 1 in kubernetes mode: runners execute every scheduled job" -}}
{{- end -}}
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

{{/* Probe for the Rundeck container: httpGet on the pod IP, or exec curl when it listens on loopback. */}}
{{- define "kestrel.rundeck.probe" -}}
{{- $loopback := index . 0 -}}{{- $path := index . 1 -}}
{{- if $loopback -}}
exec:
  command: ["curl", "-fsS", "-o", "/dev/null", "--max-time", "4", "http://127.0.0.1:4440{{ $path }}"]
{{- else -}}
httpGet: { path: {{ $path }}, port: http }
{{- end -}}
{{- end -}}

{{- define "kestrel.kubernetesMode" -}}
{{- if eq .Values.scheduler.mode "kubernetes" }}true{{ end -}}
{{- end -}}

{{- define "kestrel.runner.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kestrel.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: runner
{{- end -}}

{{- define "kestrel.runnerServiceAccountName" -}}
{{- printf "%s-runner" (include "kestrel.fullname" .) -}}
{{- end -}}

{{- define "kestrel.triggerServiceAccountName" -}}
{{- printf "%s-trigger" (include "kestrel.fullname" .) -}}
{{- end -}}

{{/*
Pod template shared by the web and runner StatefulSets. Call with (dict "root" $ "role" "web|runner").
In kubernetes mode each pod derives a stable server UUID from its StatefulSet name, so a restarted
pod marks its own interrupted executions incomplete (upstream BootStrap) and never another pod's.
*/}}
{{- define "kestrel.rundeck.pod" -}}
{{- $ := .root -}}
{{- $role := .role -}}
{{- $k8s := eq $.Values.scheduler.mode "kubernetes" -}}
{{- $auth := and (eq $role "web") $.Values.auth.enabled -}}
{{- $loopback := or (eq $role "runner") $auth -}}
{{- $res := ternary $.Values.web.resources $.Values.runner.resources (eq $role "web") -}}
metadata:
  labels:
    {{- if eq $role "web" }}
    {{- include "kestrel.web.selectorLabels" $ | nindent 4 }}
    {{- else }}
    {{- include "kestrel.runner.selectorLabels" $ | nindent 4 }}
    {{- end }}
  annotations:
    checksum/env: {{ include (print $.Template.BasePath "/web-configmap.yaml") $ | sha256sum }}
    {{- with $.Values.web.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  serviceAccountName: {{ ternary (include "kestrel.serviceAccountName" $) (include "kestrel.runnerServiceAccountName" $) (eq $role "web") }}
  {{- with $.Values.imagePullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000      # `rundeck` user in rundeck/ubuntu-base (gid 0)
    runAsGroup: 0
    fsGroup: 0
    seccompProfile:
      type: RuntimeDefault
  terminationGracePeriodSeconds: 120
  {{- if $.Values.plugins }}
  initContainers:
    - name: fetch-plugins
      image: {{ $.Values.pluginFetcher.image }}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities: { drop: ["ALL"] }
      command: ["/bin/sh", "-ec"]
      args:
        - |
          {{- range $.Values.plugins }}
          curl -fsSL --proto '=https' -o /libext/{{ .name }} {{ .url | quote }}
          echo "{{ .sha256 }}  /libext/{{ .name }}" | sha256sum -c -
          {{- end }}
      volumeMounts:
        - { name: libext, mountPath: /libext }
  {{- end }}
  containers:
    - name: rundeck
      image: {{ include "kestrel.image" $ }}
      imagePullPolicy: {{ $.Values.image.pullPolicy }}
      {{- if $k8s }}
      command: ["/tini", "--", "/bin/bash", "-ec"]
      args:
        - |
          h=$$(printf '%s' "kestrel/$${KESTREL_NAMESPACE}/$${KESTREL_POD_NAME}" | md5sum | cut -c1-32)
          export RUNDECK_SERVER_UUID="$${h:0:8}-$${h:8:4}-3$${h:13:3}-$$(printf '%x' $$(( (16#$${h:16:1} & 3) | 8 )))$${h:17:3}-$${h:20:12}"
          echo "server UUID $${RUNDECK_SERVER_UUID} for pod $${KESTREL_POD_NAME}"
          exec docker-lib/entry.sh
      {{- end }}
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: { drop: ["ALL"] }
      ports:
        # Loopback-only Rundeck (behind the sign-in sidecar, or a runner) does not own "http".
        - name: {{ ternary "rundeck" "http" $loopback }}
          containerPort: 4440
      envFrom:
        - configMapRef:
            name: {{ include "kestrel.fullname" $ }}-web-env
      env:
        {{- if $loopback }}
        - { name: RUNDECK_SERVER_ADDRESS, value: "127.0.0.1" }
        {{- end }}
        {{- if $k8s }}
        - { name: KESTREL_ROLE, value: {{ $role }} }
        - name: KESTREL_NAMESPACE
          valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
        - name: KESTREL_POD_NAME
          valueFrom: { fieldRef: { fieldPath: metadata.name } }
        {{- end }}
        {{- with $.Values.database.existingSecret }}
        - name: RUNDECK_DATABASE_USERNAME
          valueFrom: { secretKeyRef: { name: {{ . }}, key: username } }
        - name: RUNDECK_DATABASE_PASSWORD
          valueFrom: { secretKeyRef: { name: {{ . }}, key: password } }
        {{- end }}
        {{- with $.Values.keyStorage.existingSecret }}
        - name: RUNDECK_STORAGE_CONVERTER_1_CONFIG_PASSWORD
          valueFrom: { secretKeyRef: { name: {{ . }}, key: password } }
        - name: RUNDECK_CONFIG_STORAGE_CONVERTER_1_CONFIG_PASSWORD
          valueFrom: { secretKeyRef: { name: {{ . }}, key: password } }
        {{- end }}
        {{- with $.Values.web.extraEnv }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      # Grails boot + migrations can take minutes; liveness only starts after startup passes.
      startupProbe:
        {{- include "kestrel.rundeck.probe" (list $loopback "/monitoring/health/readiness") | nindent 8 }}
        periodSeconds: 10
        failureThreshold: 60
      readinessProbe:
        {{- include "kestrel.rundeck.probe" (list $loopback "/monitoring/health/readiness") | nindent 8 }}
        periodSeconds: 10
        failureThreshold: 3
      livenessProbe:
        {{- include "kestrel.rundeck.probe" (list $loopback "/monitoring/health") | nindent 8 }}
        periodSeconds: 20
        timeoutSeconds: 5
        failureThreshold: 6
      resources:
        {{- toYaml $res | nindent 8 }}
      volumeMounts:
        - { name: data, mountPath: /home/rundeck/server/data }
        - { name: logs, mountPath: /home/rundeck/var/logs }
        {{- if $.Values.plugins }}
        - { name: libext, mountPath: /home/rundeck/libext }
        {{- end }}
    {{- if $auth }}
    {{- include "kestrel.auth.container" $ | nindent 4 }}
    {{- end }}
  volumes:
    - name: data
      emptyDir: {}
    - name: logs
      emptyDir: {}
    {{- if $.Values.plugins }}
    - name: libext
      emptyDir: {}
    {{- end }}
  {{- with $.Values.web.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with $.Values.web.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with $.Values.web.affinity }}
  affinity:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with $.Values.web.topologySpreadConstraints }}
  topologySpreadConstraints:
    {{- toYaml . | nindent 4 }}
  {{- end }}
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
