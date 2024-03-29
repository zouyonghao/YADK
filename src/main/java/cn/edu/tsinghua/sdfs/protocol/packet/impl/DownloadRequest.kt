package cn.edu.tsinghua.sdfs.protocol.packet.impl

import cn.edu.tsinghua.sdfs.protocol.Codec.DOWNLOAD_REQUEST
import cn.edu.tsinghua.sdfs.protocol.packet.Packet

data class DownloadRequest(val filePath:String):Packet {
    override val command: Int
        get() = DOWNLOAD_REQUEST
}