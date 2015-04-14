package com.keepit.common.concurrent

import com.keepit.common.logging.Logging
import org.specs2.mutable.Specification
import play.api.Mode

class WatchableExecutionContextTest extends Specification with Logging {

  "WatchableExecutionContext" should {

    "can't run in prod" in {
      try {
        new WatchableExecutionContext(Mode.Prod)
      } catch {
        case e: Throwable => //good!
      }
      val good = new WatchableExecutionContext(Mode.Test) //can create
      good.kill() === 0 //cleanup
    }
  }

}

