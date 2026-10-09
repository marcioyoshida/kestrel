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
    static hasMany = [options: TOption]
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
    static belongsTo = [job: TJob]
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
