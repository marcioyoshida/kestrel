package org.rundeck.kestrel.gorm.dynamodb

import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.engine.EntityPersister
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.query.AssociationQuery
import org.grails.datastore.mapping.query.Query
import org.springframework.dao.InvalidDataAccessResourceUsageException

import java.util.regex.Pattern

/**
 * GORM query on DynamoDB.
 *
 * <p>Planning, cheapest first, using the top-level conjunction: id equality or id IN → GetItem /
 * BatchGetItem; equality or small IN on an indexed property → index table → BatchGetItem;
 * otherwise a paginated Scan. Every criterion is then evaluated in memory on the candidates, so a
 * plan only narrows, never changes, the result. Ordering, projections (including group-by),
 * offset and max are applied after filtering. Criteria the evaluator does not support raise
 * {@link InvalidDataAccessResourceUsageException} instead of returning wrong rows.
 */
@Slf4j
class DynamoQuery extends Query {
    static final int MAX_INDEXED_IN = 50

    final DynamoEntityPersister persister
    final DynamoSession dynamoSession
    /** How the last execution found its candidates (for tests and logs): id, index:<prop>, scan. */
    String plan
    /** Plan of the last query run on this thread (tests assert that indexes are used). */
    static final ThreadLocal<String> LAST_PLAN = new ThreadLocal<>()

    DynamoQuery(DynamoSession session, PersistentEntity entity, DynamoEntityPersister persister) {
        super(session, entity)
        this.dynamoSession = session
        this.persister = persister
    }

    @Override
    protected List executeQuery(PersistentEntity entity, Query.Junction criteria) {
        List<Map> rows = candidates(entity, criteria)
        LAST_PLAN.set(plan)
        rows = rows.findAll { belongsTo(entity, it) && matches(entity, it, criteria) }
        if (orderBy) {
            rows = sortRows(rows)
        }
        if (projections.isEmpty()) {
            return page(rows).collect { toObject(entity, it) }
        }
        page(project(entity, rows))
    }

    // ---------------------------------------------------------------- planning

    private List<Map> candidates(PersistentEntity entity, Query.Junction criteria) {
        if (criteria instanceof Query.Conjunction) {
            String idName = entity.identity.name
            for (Query.Criterion c : criteria.criteria) {
                if (c instanceof Query.IdEquals) {
                    plan = 'id'
                    return persister.retrieveEntries([((Query.IdEquals) c).value])
                }
                if (c instanceof Query.Equals && ((Query.Equals) c).property == idName && ((Query.Equals) c).value != null) {
                    plan = 'id'
                    return persister.retrieveEntries([idOf(((Query.Equals) c).value)])
                }
                if (c instanceof Query.In && ((Query.In) c).property == idName && ((Query.In) c).values != null) {
                    plan = 'id'
                    return persister.retrieveEntries(((Query.In) c).values.collect { idOf(it) })
                }
            }
            for (Query.Criterion c : criteria.criteria) {
                if (c instanceof Query.Equals && ((Query.Equals) c).value != null) {
                    def p = indexedProperty(entity, ((Query.Equals) c).property)
                    if (p) {
                        plan = "index:${p.name}"
                        return persister.retrieveEntries(persister.getPropertyIndexer(p).query(indexValue(p, ((Query.Equals) c).value)))
                    }
                }
                if (c instanceof Query.In && ((Query.In) c).values && ((Query.In) c).values.size() <= MAX_INDEXED_IN) {
                    def p = indexedProperty(entity, ((Query.In) c).property)
                    if (p) {
                        plan = "index:${p.name}"
                        def indexer = persister.getPropertyIndexer(p)
                        return persister.retrieveEntries(((Query.In) c).values.collectMany { indexer.query(indexValue(p, it)) }.unique())
                    }
                }
            }
        }
        plan = 'scan'
        persister.scanAll()
    }

    private PersistentProperty indexedProperty(PersistentEntity entity, String name) {
        PersistentProperty p = entity.getPropertyByName(name)
        def form = p?.mapping?.mappedForm
        (form != null && form.respondsTo('isIndex') && form.isIndex()) ? p : null
    }

    private Object indexValue(PersistentProperty p, Object value) {
        p instanceof ToOne ? idOf(value) : value
    }

    private boolean belongsTo(PersistentEntity entity, Map row) {
        if (entity.root) {
            return true
        }
        def disc = row[DynamoEntityPersister.DISCRIMINATOR]?.toString()
        if (!disc) {
            return false
        }
        def rowEntity = persister.mappingContext.getChildEntityByDiscriminator(entity.rootEntity, disc)
        for (def e = rowEntity; e != null; e = e.parentEntity) {
            if (e == entity) return true
        }
        false
    }

    // ---------------------------------------------------------------- evaluation

    boolean matches(PersistentEntity entity, Map row, Query.Criterion c) {
        switch (c) {
            case Query.Conjunction:
                return ((Query.Junction) c).criteria.every { matches(entity, row, it) }
            case Query.Disjunction:
                def cs = ((Query.Junction) c).criteria
                return cs.isEmpty() || cs.any { matches(entity, row, it) }
            case Query.Negation:
                return !((Query.Junction) c).criteria.any { matches(entity, row, it) }
            case AssociationQuery:
                return matchesAssociation(entity, row, (AssociationQuery) c)
            case Query.Exists:
                return !((Query.Exists) c).subquery.list().isEmpty()
            case Query.NotExists:
                return ((Query.NotExists) c).subquery.list().isEmpty()
            case Query.IdEquals:
                return same(row.id, idOf(((Query.IdEquals) c).value))
            case Query.PropertyComparisonCriterion:
                def pc = (Query.PropertyComparisonCriterion) c
                return compareOp(pc, value(entity, row, pc.property), value(entity, row, pc.otherProperty))
            case Query.PropertyNameCriterion:
                return matchesProperty(entity, row, (Query.PropertyNameCriterion) c)
            default:
                throw unsupported(c)
        }
    }

    private boolean matchesProperty(PersistentEntity entity, Map row, Query.PropertyNameCriterion c) {
        String name = c.property
        PersistentProperty p = entity.getPropertyByName(name.tokenize('.')[0])
        if (p instanceof Association && !(p instanceof ToOne)) {
            return matchesCollection(entity, row, (Association) p, c)
        }
        def v = value(entity, row, name)
        switch (c) {
            case Query.IsNull: return v == null
            case Query.IsNotNull: return v != null
            case Query.IsEmpty: return v == null || v == '' || (v instanceof Collection && v.isEmpty())
            case Query.IsNotEmpty: return !(v == null || v == '' || (v instanceof Collection && v.isEmpty()))
            case Query.Equals: return same(v, normalize(p, ((Query.Equals) c).value))
            case Query.NotEquals: return !same(v, normalize(p, ((Query.NotEquals) c).value))
            case Query.Between:
                def b = (Query.Between) c
                return v != null && cmp(v, normalize(p, b.from)) >= 0 && cmp(v, normalize(p, b.to)) <= 0
            case Query.GreaterThan: return v != null && cmp(v, normalize(p, ((Query.PropertyCriterion) c).value)) > 0
            case Query.GreaterThanEquals: return v != null && cmp(v, normalize(p, ((Query.PropertyCriterion) c).value)) >= 0
            case Query.LessThan: return v != null && cmp(v, normalize(p, ((Query.PropertyCriterion) c).value)) < 0
            case Query.LessThanEquals: return v != null && cmp(v, normalize(p, ((Query.PropertyCriterion) c).value)) <= 0
            case Query.ILike: return v != null && likePattern(((Query.ILike) c).value?.toString(), true).matcher(v.toString()).matches()
            case Query.Like: return v != null && likePattern(((Query.Like) c).value?.toString(), false).matcher(v.toString()).matches()
            case Query.RLike: return v != null && Pattern.compile(((Query.RLike) c).value.toString()).matcher(v.toString()).find()
            case Query.In:
                return inValues((Query.In) c).any { same(v, normalize(p, it)) }
            case Query.NotIn:
                def ni = (Query.NotIn) c
                def vals = ni.subquery != null ? ni.subquery.list() : ni.values
                return !vals.any { same(v, normalize(p, it)) }
            default:
                throw unsupported(c)
        }
    }

    private Collection inValues(Query.In c) {
        c.subquery != null ? c.subquery.list() : (c.values ?: [])
    }

    private boolean matchesCollection(PersistentEntity entity, Map row, Association a, Query.PropertyNameCriterion c) {
        int size = childIds(entity, row, a).size()
        switch (c) {
            case Query.IsEmpty: return size == 0
            case Query.IsNotEmpty: return size > 0
            case Query.SizeEquals: return size == (((Query.PropertyCriterion) c).value as int)
            case Query.SizeNotEquals: return size != (((Query.PropertyCriterion) c).value as int)
            case Query.SizeGreaterThan: return size > (((Query.PropertyCriterion) c).value as int)
            case Query.SizeGreaterThanEquals: return size >= (((Query.PropertyCriterion) c).value as int)
            case Query.SizeLessThan: return size < (((Query.PropertyCriterion) c).value as int)
            case Query.SizeLessThanEquals: return size <= (((Query.PropertyCriterion) c).value as int)
            default: throw unsupported(c)
        }
    }

    private boolean matchesAssociation(PersistentEntity entity, Map row, AssociationQuery aq) {
        Association a = aq.association
        PersistentEntity target = a.associatedEntity
        def targetPersister = (DynamoEntityPersister) dynamoSession.getPersister(target.javaClass)
        if (a instanceof ToOne) {
            def fk = row[persister.getPropertyKey(a)]
            if (fk == null) return false
            def rows = targetPersister.retrieveEntries([fk])
            return rows && matches(target, rows[0], aq.criteria)
        }
        def ids = childIds(entity, row, a)
        ids && targetPersister.retrieveEntries(ids).any { matches(target, it, aq.criteria) }
    }

    /** Child ids of a to-many association: the association index, else the inverse FK index. */
    private List childIds(PersistentEntity entity, Map row, Association a) {
        def ids = persister.getAssociationIndexer(row, a).query(row.id)
        if (ids || !a.bidirectional || !(a.inverseSide instanceof ToOne)) {
            return ids
        }
        def targetPersister = (DynamoEntityPersister) dynamoSession.getPersister(a.associatedEntity.javaClass)
        targetPersister.getPropertyIndexer(a.inverseSide).query(row.id)
    }

    // ---------------------------------------------------------------- values

    private Object value(PersistentEntity entity, Map row, String path) {
        List<String> parts = path.tokenize('.')
        PersistentProperty p = entity.getPropertyByName(parts[0])
        if (parts[0] == entity.identity.name) {
            return row.id
        }
        def v = row[p ? persister.getPropertyKey(p) : parts[0]]
        if (parts.size() == 1) {
            return v
        }
        if (p instanceof ToOne && parts.size() == 2 && parts[1] == ((ToOne) p).associatedEntity.identity.name) {
            return v  // association.id is the stored foreign key
        }
        if (p instanceof ToOne && v != null) {
            def targetPersister = (DynamoEntityPersister) dynamoSession.getPersister(((ToOne) p).associatedEntity.javaClass)
            def rows = targetPersister.retrieveEntries([v])
            return rows ? new DynamoQuery(dynamoSession, ((ToOne) p).associatedEntity, targetPersister)
                .value(((ToOne) p).associatedEntity, rows[0], parts.drop(1).join('.')) : null
        }
        def cur = v
        for (String part : parts.drop(1)) {
            cur = cur instanceof Map ? cur[part] : null  // embedded entries are maps
        }
        cur
    }

    private Object normalize(PersistentProperty p, Object v) {
        if (v instanceof GString) return v.toString()
        if (v != null && persister.mappingContext.isPersistentEntity(v)) return idOf(v)
        if (p != null && v instanceof CharSequence && p.type?.isEnum()) return v.toString()
        if (v instanceof Enum) return v
        v
    }

    private Object idOf(Object v) {
        if (v != null && persister.mappingContext.isPersistentEntity(v)) {
            return ((EntityPersister) dynamoSession.getPersister(v)).getObjectIdentifier(v)
        }
        v
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null
        if (a instanceof Number && b instanceof Number) return (a as BigDecimal) == (b as BigDecimal)
        if (a instanceof Enum || b instanceof Enum) return a.toString() == b.toString()
        if (a instanceof Date && b instanceof Date) return ((Date) a).time == ((Date) b).time
        if (a instanceof CharSequence || b instanceof CharSequence) return a.toString() == b.toString()
        a == b
    }

    static int cmp(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return (a as BigDecimal) <=> (b as BigDecimal)
        if (a instanceof Date && b instanceof Date) return ((Date) a).time <=> ((Date) b).time
        if (a instanceof Comparable && a.getClass().isInstance(b)) return ((Comparable) a) <=> b
        a.toString() <=> b?.toString()
    }

    private boolean compareOp(Query.PropertyComparisonCriterion c, Object a, Object b) {
        switch (c) {
            case Query.EqualsProperty: return same(a, b)
            case Query.NotEqualsProperty: return !same(a, b)
            case Query.GreaterThanProperty: return a != null && b != null && cmp(a, b) > 0
            case Query.GreaterThanEqualsProperty: return a != null && b != null && cmp(a, b) >= 0
            case Query.LessThanProperty: return a != null && b != null && cmp(a, b) < 0
            case Query.LessThanEqualsProperty: return a != null && b != null && cmp(a, b) <= 0
            default: throw unsupported(c)
        }
    }

    static Pattern likePattern(String sql, boolean ignoreCase) {
        StringBuilder re = new StringBuilder()
        for (char ch : (sql ?: '').toCharArray()) {
            if (ch == ('%' as char)) re.append('.*')
            else if (ch == ('_' as char)) re.append('.')
            else re.append(Pattern.quote(ch.toString()))
        }
        Pattern.compile(re.toString(), Pattern.DOTALL | (ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0))
    }

    private InvalidDataAccessResourceUsageException unsupported(Object c) {
        new InvalidDataAccessResourceUsageException("Kestrel DynamoDB datastore: unsupported criterion ${c.getClass().simpleName} on ${entity.name}")
    }

    // ---------------------------------------------------------------- results

    private List<Map> sortRows(List<Map> rows) {
        List<Map> sorted = new ArrayList<>(rows)
        sorted.sort { Map a, Map b ->
            for (Query.Order o : orderBy) {
                def va = value(entity, a, o.property)
                def vb = value(entity, b, o.property)
                if (o.ignoreCase) {
                    va = va?.toString()?.toLowerCase()
                    vb = vb?.toString()?.toLowerCase()
                }
                int r = va == null ? (vb == null ? 0 : -1) : (vb == null ? 1 : cmp(va, vb))
                if (r != 0) return o.direction == Query.Order.Direction.DESC ? -r : r
            }
            0
        }
        sorted
    }

    private List page(List rows) {
        int from = Math.max(0, offset)
        if (from >= rows.size()) return []
        int to = max < 0 ? rows.size() : Math.min(rows.size(), from + max)
        rows.subList(from, to)
    }

    private Object toObject(PersistentEntity entity, Map row) {
        def cls = entity.javaClass
        def key = row.id
        if (dynamoSession.isCached(cls, (Serializable) key)) {
            return dynamoSession.getCachedInstance(cls, (Serializable) key)
        }
        def obj = persister.createObjectFromNativeEntry(entity, (Serializable) key, row)
        dynamoSession.cacheInstance(cls, (Serializable) key, obj)
        obj
    }

    private List project(PersistentEntity entity, List<Map> rows) {
        List<Query.Projection> ps = projections.projectionList
        List<Query.GroupPropertyProjection> groups = ps.findAll { it instanceof Query.GroupPropertyProjection } as List
        if (groups) {
            Map<List, List<Map>> byKey = rows.groupBy { r -> groups.collect { value(entity, r, it.propertyName) } }
            return byKey.collect { k, rs ->
                def row = ps.collect { p -> p instanceof Query.GroupPropertyProjection ?
                    k[groups.indexOf(p)] : aggregate(entity, p, rs) }
                row.size() == 1 ? row[0] : row
            }
        }
        boolean aggregatesOnly = ps.every { !(it instanceof Query.IdProjection) && !(it.getClass() in [Query.PropertyProjection, Query.DistinctPropertyProjection]) }
        if (aggregatesOnly) {
            def row = ps.collect { aggregate(entity, it, rows) }
            return [row.size() == 1 ? row[0] : row]
        }
        if (ps.size() == 1 && ps[0] instanceof Query.DistinctPropertyProjection) {
            return rows.collect { value(entity, it, ((Query.PropertyProjection) ps[0]).propertyName) }.unique()
        }
        List out = rows.collect { r ->
            def row = ps.collect { p ->
                p instanceof Query.IdProjection ? r.id : propertyValue(entity, r, (Query.PropertyProjection) p)
            }
            row.size() == 1 ? row[0] : row
        }
        ps.any { it instanceof Query.DistinctProjection } ? out.unique() : out
    }

    private Object propertyValue(PersistentEntity entity, Map r, Query.PropertyProjection p) {
        def v = value(entity, r, p.propertyName)
        def prop = entity.getPropertyByName(p.propertyName)
        if (prop instanceof ToOne && v != null) {
            return dynamoSession.retrieve(((ToOne) prop).associatedEntity.javaClass, (Serializable) v)
        }
        v
    }

    private Object aggregate(PersistentEntity entity, Query.Projection p, List<Map> rows) {
        switch (p) {
            case Query.CountProjection: return rows.size()
            case Query.CountDistinctProjection:
                return rows.collect { value(entity, it, ((Query.PropertyProjection) p).propertyName) }.findAll { it != null }.unique().size()
            case Query.MaxProjection: return values(entity, p, rows).max { a, b -> cmp(a, b) }
            case Query.MinProjection: return values(entity, p, rows).min { a, b -> cmp(a, b) }
            case Query.SumProjection: return values(entity, p, rows).sum() ?: 0
            case Query.AvgProjection:
                def vs = values(entity, p, rows)
                return vs ? (vs.sum() as BigDecimal) / vs.size() : null
            default: throw unsupported(p)
        }
    }

    private List values(PersistentEntity entity, Query.Projection p, List<Map> rows) {
        rows.collect { value(entity, it, ((Query.PropertyProjection) p).propertyName) }.findAll { it != null }
    }
}
