package com.keepit.common.healthcheck

import org.specs2.mutable.Specification

class StackTraceTest extends Specification {

  "Stack Trace" should {
    "be created" in {
      val trace = new StackTrace()
      val crossContext = Some(new NullPointerException("i'm the cause!")).map(e => trace.withCause(e)).get
      crossContext.printStackTrace()
      crossContext.getStackTrace === trace.getStackTrace
      crossContext.getMessage === "i'm the cause!"
      crossContext.getCause.getMessage === crossContext.getMessage
    }
  }
}
