package com.keepit.search.engine

import com.keepit.common.logging.Logging

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
    def unapply(str: String): Option[Set[Long]] = {
      val parts = str.split(":", 0).toSeq
      if (parts.length > 0 && parts.head == "library") Some(parts.tail.map(_.toLong).toSet) else None
    }
  }

  object Timing {
    val flag = 0x00000004
    def unapply(str: String): Boolean = (str == "timing")
  }

  object AsNonUser {
    val flag = 0x00000008
    def unapply(str: String): Boolean = (str == "asnonuser")
  }
}

trait DebugOption { self: Logging =>
  protected var debugFlags: Int = 0
  protected var debugTracedIds: Set[Long] = null
  protected var debugLibraryIds: Set[Long] = null

  // debug flags
  def debug(debugMode: String): Unit = {
    import DebugOption._
    debugFlags = debugMode.split(",").map(_.toLowerCase.trim).foldLeft(0) { (flags, str) =>
      str match {
        case Trace(ids) =>
          debugTracedIds = ids
          flags | Trace.flag
        case Library(ids) =>
          debugLibraryIds = ids
          flags | Library.flag
        case Timing() =>
          flags | Timing.flag
        case AsNonUser() =>
          flags | AsNonUser.flag
        case _ =>
          log.warn(s"debug mode ignored: $str")
          flags
      }
    }
    log.info(s"debug flags: $debugFlags")
  }

  def debug(debugOption: DebugOption): Unit = {
    debugFlags = debugOption.debugFlags
    debugTracedIds = debugOption.debugTracedIds
    debugLibraryIds = debugOption.debugLibraryIds
  }

  def flags: Int = debugFlags

  def listLibraries(visibilityEvaluator: VisibilityEvaluator): Unit = {
    log.info(s"""NE: myLibs: ${visibilityEvaluator.myOwnLibraryIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: memberLibs: ${visibilityEvaluator.memberLibraryIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: trustedLibs: ${visibilityEvaluator.trustedLibraryIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: authorizedLibs: ${visibilityEvaluator.authorizedLibraryIds.toSeq.sorted.mkString(",")}""")
    log.info(s"""NE: myOwnLibKeepCount: ${visibilityEvaluator.myOwnLibraryKeepCount}""")
    log.info(s"""NE: memberLibKeepCount: ${visibilityEvaluator.memberLibraryKeepCount}""")
    log.info(s"""NE: trustedLibKeepCount: ${visibilityEvaluator.trustedLibraryKeepCount}""")
    log.info(s"""NE: authorizedLibKeepCount: ${visibilityEvaluator.authorizedLibraryKeepCount}""")
  }
}
