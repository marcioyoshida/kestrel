package org.rundeck.kestrel.logstore

import spock.lang.Specification

class KestrelS3LogStorePluginSpec extends Specification {
    Map<String, byte[]> objects = [:]
    ObjectStore fake = [
        put   : { String k, InputStream d, long l -> objects[k] = d.bytes },
        get   : { String k, OutputStream o -> if (!objects.containsKey(k)) return false; o.write(objects[k]); true },
        exists: { String k -> objects.containsKey(k) },
        delete: { String k -> objects.remove(k) },
    ] as ObjectStore

    KestrelS3LogStorePlugin plugin(Map ctx) {
        def p = new KestrelS3LogStorePlugin()
        p.objectStore = fake
        p.initialize(ctx)
        p
    }

    def "keys match the upstream S3 log plugin layout"() {
        expect:
        plugin([project: 'ops', execid: '44']).key('rdlog') == 'project/ops/44.rdlog'
        plugin([project: 'ops', execid: '45', execIdForLogStore: '44']).key('rdlog') == 'project/ops/44.rdlog'
    }

    def "a running execution's checkpoint is readable as partial, and the final store replaces it"() {
        given:
        def runner = plugin([project: 'ops', execid: '7'])
        def web = plugin([project: 'ops', execid: '7'])  // another pod, same execution

        when: 'the runner checkpoints'
        runner.partialStore('rdlog', new ByteArrayInputStream('line 1\n'.bytes), 7, new Date())
        def tail = new ByteArrayOutputStream()

        then: 'the web pod sees a partial log (Rundeck checks this first) and tails it'
        web.isPartialAvailable('rdlog')
        web.partialRetrieve('rdlog', tail)
        tail.toString() == 'line 1\n'
        !web.isAvailable('rdlog')

        when: 'the execution finishes'
        runner.store('rdlog', new ByteArrayInputStream('line 1\nline 2\n'.bytes), 14, new Date())
        def full = new ByteArrayOutputStream()

        then:
        web.isAvailable('rdlog')
        !web.isPartialAvailable('rdlog')
        web.retrieve('rdlog', full)
        full.toString() == 'line 1\nline 2\n'
        !objects.containsKey('project/ops/7.rdlog.partial')
    }

    def "advertises partial support so Rundeck checkpoints"() {
        expect:
        with(plugin([project: 'p', execid: '1'])) {
            partialStoreSupported && partialRetrieveSupported && storeSupported && retrieveSupported
        }
    }
}
