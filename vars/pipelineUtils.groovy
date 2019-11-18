/**
 * Various utils that help with writing more efficient pipeline code/pipeline flow control
 */
import com.cloudbees.groovy.cps.NonCPS


def checkIfMasterOrPullReq() {
    // Check SCM to ensure this is a master branch/untested PR
    // Needs to be wrapped in a 'node' statement.
    def scmVars = checkout scm

    echo(
        """
        env.CHANGE_ID:                  ${env.CHANGE_ID}
        env.BRANCH_NAME:                ${env.BRANCH_NAME}
        GIT_COMMIT:                     ${scmVars.GIT_COMMIT}
        GIT_PREVIOUS_SUCCESSFUL_COMMIT: ${scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT}
        GIT_URL:                        ${scmVars.GIT_URL}
        """
    )

    if (env.CHANGE_ID
            || (env.BRANCH_NAME == 'master'
                    && scmVars.GIT_COMMIT != scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT)
    ) {
        return true
    }

    return false
}


def runIfMasterOrPullReq(Closure body) {
    // Run the code block only after checking SCM to ensure this is a master branch/untested PR
    // Allocates/allocates a node to check scm
    def masterOrPullReq
    node {
        masterOrPullReq = checkIfMasterOrPullReq()
    }

    if (masterOrPullReq) {
        body()
    } else {
        echo 'runIfMasterOrPullReq -- not a PR or not a new commit on master.'
    }
}


def runParallel(Map<String, Closure> map) {
    /**
     * A wrapper around the 'parallel' method that tracks the pass/fail state of the individual
     * parallel stages
     *
     * Note that this executes the parallel run in a catchError block and therefore will only set
     * the build status to 'failed', it will NOT halt the execution of the build if a stage failed!
     * Therefore, you should use this method's return value and check if the list of failed stages
     * is empty, e.g.:
     *
     * if (!returnValue["failed"].isEmpty()) { error("A parallel stage failed!") }
     */
    def successStages = []
    def failedStages = []
    
    def newMap = [:]

    map.each { stageName, stageClosure ->
        def newClosure = {
            try {
                stageClosure()
                successStages.add(stageName)
            } catch (err) {
                failedStages.add(stageName)
                throw err
            }
        }
        newMap[stageName] = newClosure
    }

    catchError {
        parallel(newMap)
    }

    return ["success": successStages, "failed": failedStages]
}


@NonCPS
def getGroupedTasks(Map tasks, int groupSize) {
    // Split all tasks into groups of size 'groupSize'
    String[] taskKeys = tasks.keySet() as String[]
    int groups = ((tasks.size() + groupSize - 1) / groupSize)
    echo("Dividing tasks into ${groups} groups")
    groupedTasks = [:]
    (0..<groups).each {
        // Get the start/end index of tasks for this group
        int start = it * groupSize
        int end = start + groupSize
        if (end > tasks.size()) end = tasks.size()

        // Assign the tasks from position start-end to this group
        def groupName = "Group ${it + 1}"
        def thisGroupTasks = [:]
        (start..<end).each { index ->
            def key = taskKeys[index]
            thisGroupTasks[key] = tasks[key]
        }

        echo "Group '${groupName}' has tasks: '${thisGroupTasks.keySet()}'"
        groupedTasks[groupName] = thisGroupTasks
    }
    return groupedTasks
}


def runGroupedTasks(Map tasks, int groupSize) {
    /**
     * Run a collection of grouped tasks.
     *
     * Each group is a stage that runs tasks of max size 'groupSize' in parallel
     * Each group stage will run sequentially.
     * Results for all the tasks that ran are returned at the end
     */
    def failedTasks = []
    def successTasks = []
    groupedTasks = getGroupedTasks(tasks, groupSize)
    groupedTasks.each { name, groupTasks ->
        stage(name) {
            def results = runParallel(groupTasks)
            successTasks.addAll(results["success"])
            failedTasks.addAll(results["failed"])
        }
    }
    return ["success": successTasks, "failed": failedTasks]
}


def stageIf(def condition, String name, Closure body) {
    /**
    * This is a small improvement to reduce nested calls.
    *
    * Before:
    * if (condition) {
    *   stage("My stage") {
    *       body()
    *   }
    *
    * After:
    * stageIf(condition, "My stage") {
    *     body()   
    * }
    */
    if (condition) {
        stage(name) {
            body()
        }
    }
}


@NonCPS
private def triggeredByCommentNonCPS(currentBuild) {
    def rb = currentBuild.rawBuild
    def issueCommentCause = (
        rb.getCause(org.jenkinsci.plugins.pipeline.github.trigger.IssueCommentCause) != null
    )
    def rebuildCause = rb.getCause(com.sonyericsson.rebuild.RebuildCause) != null
    def replayCause = rb.getCause(org.jenkinsci.plugins.workflow.cps.replay.ReplayCause) != null
    def isStartedByUser = rb.getCause(hudson.model.Cause$UserIdCause) != null
    if (issueCommentCause || rebuildCause || replayCause || isStartedByUser) {
        return true
    } else {
        echo(
            "Build not started by issue comment trigger, rebuild/replay trigger, " +
            "or manual build trigger"
        )
        return false
    }
}


def triggeredByComment() {
    /**
     * Detect whether a build was triggered by a comment on a github PR
     *
     * @return Boolean
     */
    return triggeredByCommentNonCPS(currentBuild)
}


def cancelPriorBuilds() {
    /**
    * Dynamically creates milestones to ensure a new build always causes an older build to abort.
    *
    * Build #1 would define milestone(1)
    * Build #2 would define milestone(1) and milestone(2)
    * Build #3 would define milestone(2) and milestone(3)
    * Build #4 would define milestone(3) and milestone(4)
    * and so on ...
    *
    * The milestone step forces all builds to go through in order, so an older build will never be
    * allowed pass a milestone (it is aborted) if a newer build already passed it.
    */
    if (! env.priorBuildsCancelled) {
        def buildNumber = env.BUILD_NUMBER as int
        if (buildNumber > 1) {
            milestone(buildNumber - 1)
        }
        milestone(buildNumber)

        env.priorBuildsCancelled = true
    }
}
