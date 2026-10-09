# Static trigger image (~10 MB). Binaries are built outside Docker per architecture, so the
# multi-arch build needs no emulation. distroless/static provides CA certificates and uid 65532.
FROM gcr.io/distroless/static-debian12:nonroot
ARG TARGETARCH
COPY bin/kestrel-trigger-linux-${TARGETARCH} /kestrel-trigger
USER 65532:65532
ENTRYPOINT ["/kestrel-trigger"]
