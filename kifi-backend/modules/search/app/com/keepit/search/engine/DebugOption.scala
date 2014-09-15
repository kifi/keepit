package com.keepit.search.engine

import com.keepit.common.logging.Logging

object DebugOption {

  object DumpBuf {
    val flag = 0x00000001
    def unapply(str: String): Option[Set[Long]] = {
      val parts = str.split(":", 0).toSeq
      if (parts.length > 1 && parts.head == "dumpbuf") Some(parts.tail.map(_.toLong).toSet) else None
    }
  }

  object Library {
    val flag = 0x00000002
    def unapply(str: String): Option[Set[Long]] = {
      val parts = str.split(":", 0).toSeq
      if (parts.length > 0 && parts.head == "library") Some(parts.tail.map(_.toLong).toSet) else None
    }
  }
}

trait DebugOption { self: Logging =>
  protected var debugFlags: Int = 0
  protected var debugDumpBufIds: Set[Long] = null
  protected var debugLibraryIds: Set[Long] = null

  // debug flags
  def debug(debugMode: String): Unit = {
    import DebugOption._
    debugFlags = debugMode.split(",").map(_.toLowerCase.trim).foldLeft(0) { (flags, str) =>
      str match {
        case DumpBuf(ids) =>
          debugDumpBufIds = ids
          flags | DumpBuf.flag
        case Library(ids) =>
          debugLibraryIds = ids
          flags | Library.flag
        case _ =>
          log.warn(s"debug mode ignored: $str")
          flags
      }
    }
    log.info(s"debug flags: $debugFlags")
  }

  def listLibraries(libIds: (Set[Long], Set[Long], Set[Long])): Unit = {
    val (myLibIds, memberLibIds, trustedLibIds) = libIds
    log.info(s"""NE: myLibs: ${myLibIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: memberLibs: ${memberLibIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: trustedLibs: ${trustedLibIds.toSeq.sorted.mkString(",")}""")
  }
}
