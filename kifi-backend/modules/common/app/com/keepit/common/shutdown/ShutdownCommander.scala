package com.keepit.common.shutdown

import com.google.inject.{ Inject, Singleton }

@Singleton
class ShutdownCommander @Inject() () {
  private var listeners: Vector[ShutdownListener] = Vector()
  @volatile private var isShutdown = false
  def shuttingDown: Boolean = isShutdown

  def addListener(listener: ShutdownListener): Unit = synchronized {
    if (isShutdown) throw new Exception("is already shutdown")
    listeners = listeners :+ listener
  }

  def shutdown(): Unit = synchronized {
    if (isShutdown) throw new Exception("is already shutdown")
    listeners foreach { listener => listener.shutdown() }
    isShutdown = true
  }

}
