package rundeck.services.jobs

import com.dtolabs.rundeck.app.support.ScheduledExecutionQuery
import com.dtolabs.rundeck.core.plugins.configuration.Property
import org.hibernate.criterion.DetachedCriteria
import org.hibernate.criterion.Projections
import org.hibernate.criterion.Restrictions
import org.hibernate.criterion.Subqueries
import org.rundeck.app.components.jobs.JobQuery
import org.rundeck.app.components.jobs.JobQueryInput
import rundeck.Execution

class JobQueryService implements JobQuery {

    def localJobQueryService

    @Override
    Map extendCriteria(JobQueryInput input, Map params, Object delegate) {
        localJobQueryService.extendCriteria(input, params, delegate)
    }

    @Override
    List<Property> getQueryProperties() {
        localJobQueryService.getQueryProperties()
    }
}

class LocalJobQueryService {

    Map extendCriteria(JobQueryInput input, Map params, Object delegate) {
        if (org.rundeck.kestrel.app.KestrelStorage.isDynamo()) {
            return extendCriteriaPortable(input, params, delegate)
        }
        def restr = Restrictions.conjunction()
        ScheduledExecutionQuery.IS_SCHEDULED_FILTER.each { key, val ->
            if(null!=input["${key}Filter"]){
                restr.add(Restrictions.eq(val,input["${key}Filter"]))
            }
        }
        if(params.runJobLaterFilter){
            Date now = new Date()
            DetachedCriteria subquery = DetachedCriteria.forClass(Execution.class, "e").with{
                setProjection Projections.property('e.id')
                add(Restrictions.gt('e.dateStarted', now))
                add Restrictions.conjunction().
                        add(Restrictions.eqProperty('e.scheduledExecution.id', 'this.id'))
            }
            delegate.criteria.add(Restrictions.disjunction().add(Subqueries.exists(subquery)).add(restr))
        } else if(restr.conditions().size() > 0){
            delegate.criteria.add(restr)
        }
        //this returns an empty map since schedules is already considered as part of the attributes to show as part of the search
        //ergo, no need to add new values
        return [:]
    }

    /**
     * Kestrel (M2d): the same filter in GORM's criteria DSL, for the DynamoDB datastore (Hibernate
     * Restrictions and Subqueries only apply to Hibernate criteria). Jobs with a run-later execution
     * come from the sparse index of executions that have not completed.
     */
    private Map extendCriteriaPortable(JobQueryInput input, Map params, Object delegate) {
        Map<String, Object> scheduled = [:]
        ScheduledExecutionQuery.IS_SCHEDULED_FILTER.each { key, val ->
            if(null!=input["${key}Filter"]){
                scheduled[val] = input["${key}Filter"]
            }
        }
        if(params.runJobLaterFilter){
            Date now = new Date()
            Set<String> later = Execution.createCriteria().list {
                isNull('dateCompleted')
                gt('dateStarted', now)
            }*.jobUuid.findAll { it } as Set
            if (!later && !scheduled) {
                delegate.eq('id', -1L)  // nothing matches, like an EXISTS with no rows
            } else {
                delegate.or {
                    if (later) {
                        'in'('uuid', later)
                    }
                    if (scheduled) {
                        and {
                            scheduled.each { prop, value -> eq(prop, value) }
                        }
                    }
                }
            }
        } else {
            scheduled.each { prop, value -> delegate.eq(prop, value) }
        }
        return [:]
    }

    List<Property> getQueryProperties() {
        return null
    }
}
