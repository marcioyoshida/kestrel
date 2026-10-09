package org.rundeck.kestrel.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** In-memory API server with resourceVersion conflicts, for one namespace. */
class FakeKubeApi implements KubeApi {
    static final ObjectMapper JSON = new ObjectMapper()
    final Map<String, ObjectNode> objects = [:]
    int version = 1
    List<String> calls = []

    ObjectNode get(String path) {
        calls << "GET $path"
        objects[path]?.deepCopy()
    }

    ObjectNode list(String collectionPath) {
        calls << "LIST $collectionPath"
        def (base, query) = collectionPath.split('\\?') as List
        def items = objects.findAll { k, v -> k.startsWith(base + '/') }.values().findAll { o ->
            !query || o.path('metadata').path('labels').path('kestrel.io/managed-by').asText() == 'kestrel'
        }
        def list = JSON.createObjectNode()
        def arr = list.putArray('items')
        items.each { arr.add(it.deepCopy()) }
        list
    }

    ObjectNode create(String collectionPath, ObjectNode body) {
        def path = collectionPath + '/' + body.path('metadata').path('name').asText()
        calls << "CREATE $path"
        if (objects.containsKey(path)) throw new KubeApi.ConflictException(path)
        def o = body.deepCopy()
        ((ObjectNode) o.get('metadata')).put('resourceVersion', (version++).toString())
        objects[path] = o
        o.deepCopy()
    }

    ObjectNode replace(String path, ObjectNode body) {
        calls << "PUT $path"
        def cur = objects[path]
        if (cur == null) throw new IOException("404 $path")
        if (cur.path('metadata').path('resourceVersion').asText() != body.path('metadata').path('resourceVersion').asText()) {
            throw new KubeApi.ConflictException(path)
        }
        def o = body.deepCopy()
        ((ObjectNode) o.get('metadata')).put('resourceVersion', (version++).toString())
        objects[path] = o
        o.deepCopy()
    }

    void delete(String path) {
        calls << "DELETE $path"
        objects.remove(path)
    }
}
