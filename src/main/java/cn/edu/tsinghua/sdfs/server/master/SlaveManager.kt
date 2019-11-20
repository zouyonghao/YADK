package cn.edu.tsinghua.sdfs.server.master

import cn.edu.tsinghua.sdfs.Server
import cn.edu.tsinghua.sdfs.config
import cn.edu.tsinghua.sdfs.io.NetUtil
import cn.edu.tsinghua.sdfs.protocol.Codec
import cn.edu.tsinghua.sdfs.protocol.packet.impl.UserProgram
import cn.edu.tsinghua.sdfs.protocol.packet.impl.slave.DoMapPacket
import cn.edu.tsinghua.sdfs.server.mapreduce.Job
import cn.edu.tsinghua.sdfs.server.master.handler.MasterCommandHandler
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.DelimiterBasedFrameDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SlaveManager {

    private val executor = Executors.newSingleThreadScheduledExecutor()

    data class Slave(val server: Server,
                     var future: ChannelFuture?,
                     var connectionSuccess: Boolean = false
    ) {
        init {
            executor.scheduleAtFixedRate({
                if (!ok()) {
                    try {
                        NetUtil.shutdownGracefully(future?.channel())
                        println("connecting to $server")
                        future = NetUtil.connect(
                                server.ip,
                                server.port,
                                DelimiterBasedFrameDecoder(Int.MAX_VALUE, Unpooled.copiedBuffer("__\r\n__".toByteArray())),
                                MasterCommandHandler())
                        connectionSuccess = true
                    } catch (e: Exception) {
                        println("connect to $server fail")
                        e.printStackTrace()
                    }
                } else {
                    // println("slave $server already connected.")
                }
            }, 0, 5, TimeUnit.SECONDS)
        }

        fun ok(): Boolean {
            return connectionSuccess && future?.isSuccess ?: false
        }
    }

    val slaveChannels = mutableListOf<Slave>()

    fun initSlaveConnections() {
        config.slaves.forEach {
            slaveChannels.add(Slave(it, null, false))
        }
    }


    fun close() {
        executor.shutdown()
        slaveChannels.forEach {
            NetUtil.shutdownGracefully(it.future?.channel())
        }
    }

    fun uploadUserProgram(packet: UserProgram, listener: () -> Unit) {
        // TODO("add queue to each Slave in order to resume when slave down")
        var uploadCount = 0
        var uploadedCount = 0
        slaveChannels.filter { it.ok() }.forEach {
            val channel = it.future!!.channel()
            uploadCount++
            Codec.writeAndFlushPacket(channel, packet)
                    .addListener {
                        // println(uploadedCount)
                        uploadedCount++
                        if (uploadCount == uploadedCount) {
                            listener.invoke()
                        }
                    }
        }
    }

    fun doMap(job: Job, slaves: MutableList<Server>, partition: Int): Server? {
        val slave = slaveChannels.find { it.ok() && slaves.contains(it.server) } ?: return null
        Codec.writeAndFlushPacket(slave.future!!.channel(), DoMapPacket(job, partition))
        return slave.server
    }
}