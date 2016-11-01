package org.roylance.yadel.api.actors

import akka.actor.ActorRef
import akka.actor.Terminated
import akka.actor.UntypedActor
import akka.event.Logging
import akka.event.LoggingAdapter
import org.joda.time.LocalDateTime
import org.joda.time.Minutes
import org.roylance.yadel.YadelModel
import org.roylance.yadel.YadelReport
import org.roylance.yadel.api.models.ConfigurationActorRef
import org.roylance.yadel.api.utilities.DagUtilities
import scala.concurrent.duration.Duration
import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.TimeUnit

abstract class ManagerBase: UntypedActor() {
    protected val workers = HashMap<String, ConfigurationActorRef>()
    protected val activeDags = HashMap<String, YadelModel.Dag.Builder>()

    protected val log: LoggingAdapter = Logging.getLogger(this.context().system(), this)

    override fun preStart() {
        System.out.println("max memory: ${ManagementFactory.getMemoryMXBean().heapMemoryUsage.max / 1000000} MB")
        val runnable = Runnable {
            this.self.tell(YadelModel.ManagerToManagerMessageType.ENSURE_WORKERS_WORKING, this.self)
        }
        this.context.system().scheduler().schedule(OneMinute, OneMinute, runnable,this.context.system().dispatcher())
    }

    override fun onReceive(p0: Any?) {
        // is this a dag from someone?
        if (p0 is YadelModel.Dag) {
            this.activeDags[p0.id] = p0.toBuilder()
        }
        else if (p0 is YadelModel.AddTaskToDag && p0.hasNewTask() &&
                this.activeDags.containsKey(p0.newTask.dagId)) {
            this.log.info("looking for ${p0.newTask.dagId}")
            val foundDag = this.activeDags[p0.newTask.dagId]!!
            foundDag.addUncompletedTasks(p0.newTask).addFlattenedTasks(p0.newTask)
            this.log.info("saved task to dag")
        }
        else if (p0 is YadelModel.CompleteTask && this.activeDags.containsKey(p0.task.dagId)) {
            this.updateSenderStatus(YadelModel.WorkerState.IDLE)

            val foundDagBuilder = this.activeDags[p0.task.dagId]!!
            val existingTask = foundDagBuilder.processingTasksBuilderList.firstOrNull { it.id == p0.task.id }
            if (existingTask != null) {
                val indexToRemove = foundDagBuilder.processingTasksBuilderList.indexOf(existingTask)
                foundDagBuilder.removeProcessingTasks(indexToRemove)
            }
            else {
                this.log.info("could not find ${p0.task.id} in the processing tasks, for some reason...")
            }

            if (p0.isError) {
                foundDagBuilder.addErroredTasks(p0.task)
            }
            else {
                foundDagBuilder.addCompletedTasks(p0.task)
            }
        }
        else if (p0 is YadelModel.WorkerToManagerMessageType &&
                YadelModel.WorkerToManagerMessageType.REGISTRATION == p0) {
            this.log.info("handling registration")
            this.handleRegistration()
        }
        else if (p0 is Terminated) {
            this.log.info("handling termination")
            this.handleTermination(p0)
        }
        else if (p0 is YadelReport.UIYadelRequest) {
            if (p0.requestType == YadelReport.UIYadelRequestType.REPORT_DAGS) {
                this.handleReport()
            }
            if (p0.requestType == YadelReport.UIYadelRequestType.DELETE_DAG &&
                this.activeDags.containsKey(p0.dagId)) {
                this.log.info("attempting to remove ${p0.dagId}")
                this.activeDags.remove(p0.dagId)
            }
            if (p0.requestType == YadelReport.UIYadelRequestType.GET_DAG_STATUS &&
                activeDags.containsKey(p0.dagId)) {
                val uiDag = DagUtilities.buildUIDagFromDag(activeDags[p0.dagId]!!)
                sender.tell(uiDag.build(), self)
            }
        }
        else if (p0 is YadelReport.UIYadelRequestType &&
                YadelReport.UIYadelRequestType.REPORT_DAGS == p0) {
            handleReport()
        }
        else if (p0 is YadelModel.ManagerToManagerMessageType &&
                YadelModel.ManagerToManagerMessageType.ENSURE_WORKERS_WORKING == p0) {
            this.log.info("Have ${this.activeDags.size} active dags with ${this.workers.size} workers:")

            this.activeDags.values.forEach { actualDag ->
                val availableTaskIds = DagUtilities.getAllAvailableTaskIds(actualDag)
                this.log.info("${actualDag.display} (${actualDag.id})")
                this.log.info("${actualDag.uncompletedTasksCount} uncompleted tasks")
                this.log.info("${actualDag.processingTasksCount} processing tasks")
                this.log.info("${actualDag.erroredTasksCount} error tasks")
                this.log.info("${actualDag.completedTasksCount} completed tasks")

                this.log.info("${availableTaskIds.size} task(s) can be executed right now with ${this.workers.values.count { it.configuration.state == YadelModel.WorkerState.IDLE }} idle worker(s)")
            }
            this.workers.values.forEach {
                this.log.info("${it.actorRef.path()}: ${it.configuration.state.name}")
            }
        }

        this.tellWorkersToDoNewDagWork()
        this.freeAnyWorkersRunningOverAllowedAmount()
    }

    protected fun tellWorkersToDoNewDagWork() {
        if (this.workers.values.filter { it.configuration.state == YadelModel.WorkerState.IDLE }.size == 0) {
            return
        }

        activeDags.values.forEach { foundActiveDag ->
            if (!DagUtilities.canProcessDag(foundActiveDag, activeDags)) {
                return@forEach
            }

            val taskIdsToProcess = DagUtilities.getAllAvailableTaskIds(foundActiveDag)
            taskIdsToProcess.forEach { uncompletedTaskId ->
                val openWorker = getOpenWorker()
                if (openWorker != null) {
                    val workerKey = getActorRefKey(openWorker.actorRef)
                    val foundTuple = workers[workerKey]
                    val newBuilder = foundTuple!!.configuration.toBuilder()
                    newBuilder.state = YadelModel.WorkerState.WORKING

                    val task = foundActiveDag.uncompletedTasksBuilderList.first { it.id == uncompletedTaskId }
                    val taskIdx = foundActiveDag.uncompletedTasksBuilderList.indexOf(task!!)
                    foundActiveDag.removeUncompletedTasks(taskIdx)

                    val updatedTask = task.setExecutionDate(Date().time).setStartDate(Date().time)
                    foundActiveDag.addProcessingTasks(updatedTask.build())

                    newBuilder.task = updatedTask.build()
                    newBuilder.dag = foundActiveDag.build()
                    newBuilder.taskStartTime = LocalDateTime.now().toString()

                    workers[workerKey] = ConfigurationActorRef(openWorker.actorRef, newBuilder.build())
                    openWorker.actorRef.tell(task.build(), self)
                }
            }
        }
    }

    private fun handleReport() {
        val dagReport = YadelReport.UIDagReport.newBuilder()

        workers.values.forEach {
            dagReport.addWorkers(DagUtilities.buildWorkerConfiguration(it))
        }

        val rootDags = DagUtilities.buildDagTree(activeDags)
        dagReport.addAllDags(rootDags)

        // respond with message, but encode in base 64
        sender.tell(dagReport.build(), self)
    }

    private fun updateSenderStatus(newState:YadelModel.WorkerState) {
        val workerKey = this.getActorRefKey(sender)
        if (this.workers.containsKey(workerKey)) {
            val foundTuple = this.workers[workerKey]
            val newBuilder = foundTuple!!.configuration.toBuilder()
            newBuilder.state = newState
            newBuilder.clearDag()
            newBuilder.clearTask()
            newBuilder.clearTaskStartTime()

            this.workers[workerKey] = ConfigurationActorRef(sender, newBuilder.build())
        }
    }

    protected fun getOpenWorker():ConfigurationActorRef? {
        val openWorkers = this.workers.values.filter { it.configuration.state == YadelModel.WorkerState.IDLE }
        if (openWorkers.isEmpty()) {
            return null
        }
        return openWorkers.first()
    }

    private fun freeAnyWorkersRunningOverAllowedAmount() {
        val now = LocalDateTime.now()

        val workersToReset = HashSet<String>()
        this.workers.keys.forEach {
            val worker = this.workers[it]!!

            if (worker.configuration.state == YadelModel.WorkerState.WORKING) {
                val taskStartTime = LocalDateTime.parse(worker.configuration.taskStartTime)

                val minutes = Minutes.minutesBetween(taskStartTime, now).minutes
                if (minutes > MinutesBeforeTaskReset) {
                    if (this.activeDags.containsKey(worker.configuration.dag.id)) {
                        val activeDag = this.activeDags[worker.configuration.dag.id]!!
                        val foundTask = activeDag.processingTasksList.firstOrNull { it.id == worker.configuration.task.id }

                        if (foundTask != null) {
                            if (!foundTask.isWaitingForAnotherDagTask) {
                                workersToReset.add(it)
                                val foundTaskIdx = activeDag.processingTasksList.indexOf(foundTask)

                                activeDag.removeProcessingTasks(foundTaskIdx)
                                activeDag.addUncompletedTasks(foundTask)
                            }
                        }
                    }
                }
            }
        }

        workersToReset.forEach {
            val foundWorker = this.workers[it]!!

            val tempConfiguration = foundWorker.configuration.toBuilder()
            tempConfiguration.state = YadelModel.WorkerState.IDLE
            tempConfiguration.clearDag()
            tempConfiguration.clearTask()
            tempConfiguration.clearTaskStartTime()

            this.workers[it] = ConfigurationActorRef(foundWorker.actorRef, tempConfiguration.build())
        }
    }

    private fun handleRegistration() {
        this.context.watch(this.sender)
        val key = this.getActorRefKey(this.sender)

        val newConfiguration = YadelModel.WorkerConfiguration.newBuilder()
                .setHost(this.sender.path().address().host().get())
                .setPort(this.sender.path().address().port().get().toString())
                .setIp(key)
                .setInitializedTime(LocalDateTime.now().toString())
                .setMinutesBeforeTaskReset(MinutesBeforeTaskReset)
                .setState(YadelModel.WorkerState.IDLE)
        val tuple = ConfigurationActorRef(this.sender, newConfiguration.build())
        this.workers.put(key, tuple)
    }

    private fun handleTermination(terminated: Terminated) {
        val key = this.getActorRefKey(terminated.actor)
        if (this.workers.containsKey(key)) {
            this.workers.remove(key)
        }
    }

    private fun getActorRefKey(actorRef: ActorRef):String {
        return actorRef.path().address().toString()
    }

    companion object {
        private val OneMinute = Duration.create(1, TimeUnit.MINUTES)
        private val MinutesBeforeTaskReset = 20L
    }
}
