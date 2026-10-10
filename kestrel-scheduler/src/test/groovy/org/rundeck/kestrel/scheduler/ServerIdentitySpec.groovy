package org.rundeck.kestrel.scheduler

import spock.lang.Specification

class ServerIdentitySpec extends Specification {
    def "matches the UUIDs the chart's pod command derives (deploy/helm/kestrel/ci/test.sh)"() {
        expect:
        ServerIdentity.uuidFor('kestrel', 'kestrel-web-0') == 'f857649f-5647-3c65-9bf4-02ca7765d96c'
        ServerIdentity.uuidFor('kestrel', 'kestrel-web-1') == '3e264e16-5659-3ec1-a2ec-c8e265f56e7c'
        UUID.fromString(ServerIdentity.uuidFor('ns', 'kestrel-runner-3')).version() == 3
    }
}
