package com.keepit.common.healthcheck

//writableStackTrace == true. that's the whole point!
class StackTrace() extends Throwable("Stack Trace") {
  def withCause(cause: Throwable): Throwable = {
    new CrossContextException(cause).withStackTrace(getStackTrace)
  }
}

private class CrossContextException(cause: Throwable)
    extends Throwable(cause.getMessage, cause) {

  def withStackTrace(stackTrace: Array[StackTraceElement]): CrossContextException = {
    this.setStackTrace(stackTrace)
    this
  }

  override def toString(): String = cause.toString
}
