package com.keepit.search.engine

object DebugOption {

  object DumpBuf {
    val flag = 0x00000001
    def unapply(str: String): Option[Set[Long]] = {
      val parts = str.split(":", 0).toSeq
      if (parts.length > 1 && parts.head == "dumpbuf") Some(parts.tail.map(_.toLong).toSet) else None
    }
  }

}
