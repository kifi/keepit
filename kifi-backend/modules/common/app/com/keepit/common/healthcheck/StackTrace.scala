package com.keepit.common.healthcheck

//writableStackTrace == true. that's the whole point!
class StackTrace() extends Exception("Stack Trace") {
  def withCause(cause: Throwable): Throwable = {
    if (cause == this) throw new IllegalStateException("Setting cause as itself!")
    new CrossContextException(cause).withStackTrace(getStackTrace)
  }
}

private class CrossContextException(cause: Throwable)
    extends Exception(cause.getMessage, cause) {

  def withStackTrace(stackTrace: Array[StackTraceElement]): CrossContextException = {
    this.setStackTrace(stackTrace)
    this
  }

  override def toString(): String = cause.toString
}
