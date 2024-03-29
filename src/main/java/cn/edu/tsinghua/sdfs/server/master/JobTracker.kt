package cn.edu.tsinghua.sdfs.server.master

import cn.edu.tsinghua.sdfs.protocol.packet.impl.JobResultQuery
import cn.edu.tsinghua.sdfs.protocol.packet.impl.UserProgram
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.DoMapPacket
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.DoReducePacket
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.GetReduceResult
import cn.edu.tsinghua.sdfs.server.mapreduce.Job
import cn.edu.tsinghua.sdfs.server.mapreduce.JobStatus.FAIL
import cn.edu.tsinghua.sdfs.server.mapreduce.JobStatus.FINISHED
import cn.edu.tsinghua.sdfs.server.mapreduce.JobStatus.INIT
import cn.edu.tsinghua.sdfs.server.mapreduce.JobStatus.RUNNING
import cn.edu.tsinghua.sdfs.server.mapreduce.JobStatus.SUSPEND
import cn.edu.tsinghua.sdfs.user.program.ScriptRunner
import com.alibaba.fastjson.JSON
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object JobTracker {
    lateinit var ROOT_DIR: Path

    private const val userProgramDir = "__job__"

    private val jobMap = mutableMapOf<String, Job>()

    private val jobExecutor = Executors.newScheduledThreadPool(1)

    init {
        jobExecutor.scheduleAtFixedRate({
            try {
                val jobNeedRemove = mutableListOf<Job>()
                jobMap.values.forEach {
                    println("job ${it.id} status is ${it.status}")
                    when (it.status) {
                        INIT -> {
                            it.status = RUNNING
                        }
                        RUNNING -> {
                            if (it.jobContext.currentPc >= it.jobContext.functions!!.size) {
                                it.status = FINISHED
                                return@forEach
                            }
                            it.jobContext.currentPc++
                            runCurrentFunc(it)
                        }
                        FAIL -> {
                            println("Job $it failed.")
                            jobNeedRemove.add(it)
                        }
                        FINISHED -> jobNeedRemove.add(it)
                        SUSPEND -> {
                            // println("job still suspend")
                        }
                    }
                    saveJob(it)
                }
                jobMap.values.removeAll(jobNeedRemove)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 0, 3, TimeUnit.SECONDS)
    }

    fun startJob(userProgram: UserProgram) {
        println("start job ${userProgram.id}")
        val job = Job(userProgram.id, userProgram, INIT)
        job.jobContext = ScriptRunner.compile(userProgram.content)
        submitJob(job)
    }

    private fun submitJob(job: Job) {
        println("submit job ${job.id}")
        if (job.jobContext.file.isEmpty()) {
            println("No file specified in script ${job.userProgram.id}, exit")
            job.status = FAIL
            return
        }
        if (!NameManager.getNameItem(job.jobContext.file).exist) {
            println("File specified in script ${job.userProgram.id} not exist, exit")
            job.status = FAIL
            return
        }
        // job executed in jobExecutor
        jobMap.putIfAbsent(job.id, job)
    }

    private fun runCurrentFunc(job: Job) {
        val pair = job.jobContext.functions!![job.jobContext.currentPc]
        when (pair.first) {
            "map" -> {
                val nameItem = NameManager.getNameItem(job.jobContext.file)
                nameItem.partitions.forEachIndexed { index, slaves ->
                    val slave = SlaveManager.doMap(job, slaves, index) ?: TODO("retry")
                    println("running a map job with index $index on $slave")
                }
            }
            "reduce" -> {
                if (job.jobContext.mapIntermediateFiles.isEmpty()) {
                    // TODO: support reduce first
                }

                job.jobContext.mapIntermediateFiles.forEach { (reducePartition, files) ->
                    val slave = SlaveManager.doReduce(job, reducePartition, files)
                    println("running a reduce job with index $reducePartition on $slave")
                }
            }
        }
        job.status = SUSPEND
    }

    fun mapFinished(packet: DoMapPacket) {
        synchronized(JobTracker) {
            jobMap[packet.job.id]?.apply {
                packet.job.jobContext.mapIntermediateFiles.forEach { (reducePartition, intermediateFiles) ->
                    jobContext.mapIntermediateFiles.putIfAbsent(reducePartition, intermediateFiles)
                    jobContext.mapIntermediateFiles[reducePartition]!!.addAll(intermediateFiles)
                }

                jobContext.finishedMapper.add(packet.slave)
                println("partition ${packet.partition}")
                if (jobContext.finishedMapper.containsAll(jobContext.mapper) &&
                        jobContext.finishedMapper.size == jobContext.mapper.size) {
                    println("all mapper finished!")
                    this.jobContext.currentPc = packet.job.jobContext.currentPc
                    this.status = RUNNING
                }
            }
        }
    }

    fun reduceFinished(packet: DoReducePacket) {
        synchronized(JobTracker) {
            jobMap[packet.job.id]?.apply {
                packet.job.jobContext.reduceResultFiles.forEach { (reducePartition, intermediateFiles) ->
                    jobContext.reduceResultFiles.putIfAbsent(reducePartition, intermediateFiles)
                    jobContext.reduceResultFiles[reducePartition]!!.addAll(intermediateFiles)
                }

                jobContext.finishedReducer.add(packet.server)
                if (jobContext.finishedReducer.containsAll(jobContext.reducer) &&
                        jobContext.finishedReducer.size == jobContext.reducer.size) {
                    println("all reducer finished")
                    this.jobContext.currentPc = packet.job.jobContext.currentPc
                    this.status = RUNNING
                }
            }
        }
    }

    fun saveJob(job: Job) {
        val path = Paths.get(ROOT_DIR.toString(), userProgramDir)
        if (Files.notExists(path)) {
            Files.createDirectories(path)
        }
        Files.write(Paths.get(path.toString(), "${job.id}.json"), JSON.toJSONBytes(job))
    }

    fun getJob(id: String): Job? {
        val path = Paths.get(ROOT_DIR.toString(), userProgramDir, "$id.json")
        if (Files.exists(path)) {
            return JSON.parseObject(String(Files.readAllBytes(path)), Job::class.java)
        }
        return null
    }

    // channel id : { reduce index : ["result1", "result2"]}
    // todo: three map to object, fix this ugly implement
    val channelIdToReducerQueryMap = ConcurrentHashMap<String, MutableMap<Int, MutableList<String>>>()
    val channelIdToReducerCount = ConcurrentHashMap<String, Int>()
    val channelIdToCountDownLatch = ConcurrentHashMap<String, CountDownLatch>()
    fun getReduceResult(channelId: String, packet: JobResultQuery): String {
        val job = getJob(packet.id) ?: return "job ${packet.id} doesn't exist"

        channelIdToReducerQueryMap.putIfAbsent(channelId, mutableMapOf())
        var count = 0
        job.jobContext.reduceResultFiles.forEach { (_, intermediateFiles) ->
            run {
                intermediateFiles.forEach { _ ->
                    count++
                }
            }
        }
        channelIdToReducerCount.putIfAbsent(channelId, count)
        channelIdToCountDownLatch.putIfAbsent(channelId, CountDownLatch(1))

        // reduce index : ["result1", "result2"]
        val resultMap = mutableMapOf<Int, MutableList<String>>()
        channelIdToReducerQueryMap[channelId] = resultMap
        job.jobContext.reduceResultFiles.forEach { (reduceIndex, intermediateFiles) ->
            run {
                resultMap.putIfAbsent(reduceIndex, mutableListOf())
                intermediateFiles.forEach {
                    SlaveManager.getReduceResult(it.server, it.file, channelId, reduceIndex)
                }
            }
        }

        channelIdToCountDownLatch[channelId]!!.await()

        val returnResult = JSON.toJSONString(channelIdToReducerQueryMap[channelId])
        channelIdToReducerQueryMap.remove(channelId)
        channelIdToReducerCount.remove(channelId)
        channelIdToCountDownLatch.remove(channelId)
        return returnResult
    }

    fun receiveReduceResult(packet: GetReduceResult) {
        synchronized(JobTracker) {
            channelIdToReducerQueryMap[packet.channelId]!![packet.reduceId]!!.add(packet.result)
            var count = 0
            channelIdToReducerQueryMap[packet.channelId]!!.forEach { (_, results) ->
                run {
                    results.forEach { _ ->
                        count++
                    }
                }
            }
            // println(count)
            // println(channelIdToReducerCount[packet.channelId])
            if (count == channelIdToReducerCount[packet.channelId]) {
                channelIdToCountDownLatch[packet.channelId]!!.countDown()
            }
        }
    }
}