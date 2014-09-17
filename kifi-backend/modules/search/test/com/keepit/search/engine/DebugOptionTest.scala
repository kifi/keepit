package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.DebugOption._
import org.specs2.mutable.Specification

class DebugOptionTest extends Specification {

  def debugOption = new DebugOption with Logging {
    def ids = debugDumpBufIds
  }

  "DebugOption" should {
    "parse dumpbuf" in {
      val ids = "dumpbuf:1:100:1000" match {
        case DumpBuf(ids) => Some(ids)
        case _ => None
      }

      ids must beSome[Set[Long]]
      ids.get === Set(1L, 100L, 1000L)
    }

    "parse timing" in {
      val res = "timing" match {
        case Timing() => true
        case _ => false
      }

      res === true
    }

    "ignore bogus options" in {
      var obj = debugOption
      obj.debug("foo,dumpbuf, bar")
      (obj.flags & DumpBuf.flag) === 0

      obj = debugOption
      obj.debug("foo, dumpbuf:2:3,bar")
      ((obj.flags & DumpBuf.flag) != 0) === true
    }
  }
}
