package com.keepit.search.engine

import com.keepit.common.logging.Logging
import com.keepit.search.engine.DebugOption._
import org.specs2.mutable.Specification

class DebugOptionTest extends Specification {

  def debugOption = new DebugOption with Logging {
    def ids = debugTracedIds
  }

  "DebugOption" should {
    "parse trace" in {
      val ids = "trace:1:100:1000" match {
        case Trace(ids) => Some(ids)
        case _ => None
      }

      ids must beSome[Set[Long]]
      ids.get === Set(1L, 100L, 1000L)
    }

    "parse library" in {
      val res = "library" match {
        case Library() => true
        case _ => false
      }

      res === true
    }

    "ignore bogus options" in {
      var obj = debugOption
      obj.debug("foo,trace, bar")
      (obj.debugFlags & Trace.flag) === 0

      obj = debugOption
      obj.debug("foo, trace:2:3,bar")
      ((obj.debugFlags & Trace.flag) != 0) === true
    }
  }
}
