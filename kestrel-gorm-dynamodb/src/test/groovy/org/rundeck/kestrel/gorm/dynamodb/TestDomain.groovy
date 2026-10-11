package org.rundeck.kestrel.gorm.dynamodb

import grails.gorm.annotation.Entity

/** Shapes modelled on Rundeck's ScheduledExecution / Option / Execution / WorkflowStep. */
@Entity
class TJob {
    String uuid
    String project
    String jobName
    String groupPath
    Boolean scheduled = false
    TStatus state = TStatus.ACTIVE
    Date dateCreated
    Date lastRun
    Long version
    static hasMany = [options: TOption, execs: TExec]  // execs: inverse of TExec.job, as ScheduledExecution.executions
    static constraints = {
        groupPath nullable: true
        dateCreated nullable: true
        lastRun nullable: true
    }
}

@Entity
class TOption {
    String name
    Boolean required = false
    String valuesUrl
    static belongsTo = [job: TJob]
    static constraints = {  // as Option.valuesUrl
        valuesUrl(nullable: true, validator: { String val, TOption obj ->
            if (val) {
                try { new URL(val) } catch (MalformedURLException e) { return 'option.valuesUrl.invalid.message' }
            }
            true
        })
    }
}

@Entity
class TExec {
    String project
    String status
    String user
    Date dateStarted
    Date dateCompleted
    Integer retryAttempt = 0
    TJob job
    static constraints = {
        job nullable: true
        dateCompleted nullable: true
    }
}

@Entity
abstract class TStep {
    String description
    static belongsTo = [TWorkflow, TFlow]  // as WorkflowStep belongsTo [Workflow, WorkflowStep]
    static constraints = { description nullable: true }
}

@Entity
class TCmdStep extends TStep {
    String command
}

@Entity
class TJobStep extends TStep {
    String jobRef
}

enum TStatus { ACTIVE, DISABLED }

/** Like Rundeck's AuthToken: opted in with mapWith, looked up by token inside an or-block. */
@Entity
class TToken {
    static mapWith = 'dynamodb'
    String token
    String mode
    String creator
    static constraints = {
        token unique: true  // as AuthToken.token, ScheduledExecution.uuid, Project.name
    }
}

/** Like Rundeck's Workflow: an ordered list of steps. */
@Entity
class TWorkflow {
    String strategy = 'node-first'
    List<TStep> commands
    static hasMany = [commands: TStep]
}

/** Like Rundeck's ReferencedExecution: a child pointing at an execution, plus a job uuid. */
@Entity
class TRef {
    String jobUuid
    TExec execution
}

/** Like ScheduledExecution -> Workflow -> steps: a to-one owned child (lazy proxy when loaded) with ordered steps. */
@Entity
class TOwner {
    String name
    TFlow flow
}

@Entity
class TFlow {
    String strategy = 'node-first'
    List<TStep> commands
    static belongsTo = [TOwner]
    static hasMany = [commands: TStep]
}
