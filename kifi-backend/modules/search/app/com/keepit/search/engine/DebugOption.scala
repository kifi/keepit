package com.keepit.search.engine

import com.keepit.common.logging.Logging
import java.net.{ DatagramPacket, DatagramSocket, InetAddress }

object DebugOption {

  object Trace {
    val flag = 0x00000001
    def unapply(str: String): Option[Set[Long]] = {
      val parts = str.split(":", 0).toSeq
      if (parts.length > 1 && parts.head == "trace") Some(parts.tail.map(_.toLong).toSet) else None
    }
  }

  object Library {
    val flag = 0x00000002
    def unapply(str: String): Boolean = (str == "library")
  }

  object NoDirectPath {
    val flag = 0x00000004
    def unapply(str: String): Boolean = (str == "nodirectpath")
  }

  object AsNonUser {
    val flag = 0x00000008
    def unapply(str: String): Boolean = (str == "asnonuser")
  }

  object Log {
    val flag = 0x00000010
    def unapply(str: String): Option[(InetAddress, Int)] = {
      val parts = str.split(":", 0)
      if (parts.length == 3 && parts(0) == "log") Some((InetAddress.getByName(parts(1)), parts(2).toInt)) else None
    }
  }

  private[engine] lazy val socket = new DatagramSocket()
}

trait DebugOption { self: Logging =>
  protected var debugLogDestination: (InetAddress, Int) = null
  protected var debugStartTime: Long = System.currentTimeMillis()
  var debugFlags: Int = 0
  var debugTracedIds: Set[Long] = null

  // debug flags
  def debug(debugMode: String): Unit = {
    import DebugOption._
    debugFlags = debugMode.split(",").map(_.toLowerCase.trim).foldLeft(0) { (flags, str) =>
      str match {
        case Trace(ids) =>
          debugTracedIds = ids
          flags | Trace.flag
        case Library() =>
          flags | Library.flag
        case NoDirectPath() =>
          flags | NoDirectPath.flag
        case AsNonUser() =>
          flags | AsNonUser.flag
        case Log(address, port) =>
          debugLogDestination = (address, port)
          flags | Log.flag
        case _ =>
          log.warn(s"debug mode ignored: $str")
          flags
      }
    }
    log.info(s"debug flags: $debugFlags")
  }

  def debug(debugOption: DebugOption): Unit = {
    debugStartTime = debugOption.debugStartTime
    debugFlags = debugOption.debugFlags
    debugTracedIds = debugOption.debugTracedIds
    debugLogDestination = debugOption.debugLogDestination
  }

  def debugLog(msg: => String) = {
    if ((debugFlags & DebugOption.Log.flag) != 0) {
      val elapsed = (System.currentTimeMillis() - debugStartTime)
      val bytes = s"$elapsed: ${msg}".getBytes()
      val packet = new DatagramPacket(bytes, bytes.length, debugLogDestination._1, debugLogDestination._2)
      DebugOption.socket.send(packet)
    }
  }
}
