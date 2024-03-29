package cn.edu.tsinghua.sdfs.server.master.handler

import cn.edu.tsinghua.sdfs.exception.WrongCodecException
import cn.edu.tsinghua.sdfs.protocol.Codec
import cn.edu.tsinghua.sdfs.protocol.packet.impl.CreateRequest
import cn.edu.tsinghua.sdfs.protocol.packet.impl.DownloadRequest
import cn.edu.tsinghua.sdfs.protocol.packet.impl.JobResultQuery
import cn.edu.tsinghua.sdfs.protocol.packet.impl.JobStatusQuery
import cn.edu.tsinghua.sdfs.protocol.packet.impl.LsPacket
import cn.edu.tsinghua.sdfs.protocol.packet.impl.ResultToClient
import cn.edu.tsinghua.sdfs.protocol.packet.impl.UserProgram
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.DoMapPacket
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.DoReducePacket
import cn.edu.tsinghua.sdfs.protocol.packet.impl.mapreduce.GetReduceResult
import cn.edu.tsinghua.sdfs.server.mapreduce.UserProgramManager
import cn.edu.tsinghua.sdfs.server.master.JobTracker
import cn.edu.tsinghua.sdfs.server.master.NameManager
import cn.edu.tsinghua.sdfs.server.master.SlaveManager
import com.alibaba.fastjson.JSON
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

class MasterCommandHandler : ChannelInboundHandlerAdapter() {

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val byteBuf = msg as ByteBuf
        val type = byteBuf.getInt(0)
        if (type != Codec.TYPE) {
            throw WrongCodecException()
        }
        when (val packet = Codec.decode(byteBuf)) {
            is CreateRequest -> {
                val nameItem = NameManager.createOrGet(packet.remoteFile, packet.fileLength)
                Codec.writeAndFlushPacket(ctx.channel(), nameItem)
            }
            is LsPacket -> {
                Codec.writeAndFlushPacket(ctx.channel(), ResultToClient(NameManager.ls(packet.path)))
            }
            is DownloadRequest -> {
                Codec.writeAndFlushPacket(ctx.channel(), NameManager.getNameItem(packet.filePath))
            }
            is UserProgram -> {
                println("receive user program ${packet.id}")
                UserProgramManager.saveUserProgram(packet)
                SlaveManager.uploadUserProgram(packet) {
                    JobTracker.startJob(packet)
                }
            }
            // map task finish from a mapper
            is DoMapPacket -> {
                println("receive map packet with job id ${packet.job.id}")
                JobTracker.mapFinished(packet)
            }
            // reduce task finish from a reducer
            is DoReducePacket -> {
                println("receive reduce packet with job id ${packet.job.id}")
                JobTracker.reduceFinished(packet)
            }

            is JobStatusQuery -> {
                println("receive job query ${packet.id}")
                Codec.writeAndFlushPacket(ctx.channel(), ResultToClient(
                        JSON.toJSONString(JobTracker.getJob(packet.id), true))
                )
            }

            is JobResultQuery -> {
                println("receive job result ${packet.id}")
                Codec.writeAndFlushPacket(ctx.channel(), ResultToClient(
                        JobTracker.getReduceResult(ctx.channel().id().asLongText(), packet)))
            }

            is GetReduceResult -> {
                println("receive reduce result for channel ${packet.channelId}")
                JobTracker.receiveReduceResult(packet)
            }
        }
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        super.channelUnregistered(ctx)
        SlaveManager.slaveChannelUnregister(ctx.channel())
        println("channel unregistered")
    }
}
