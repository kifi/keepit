package com.keepit.common.db

import scala.slick.session.{Database => SlickDatabase}

import com.google.inject.Singleton
import com.keepit.common.db.slick.SlickSessionProviderImpl

@Singleton
class TestSlickSessionProvider extends SlickSessionProviderImpl {

  private[this] var _readOnlySessionsCreated = 0
  def readOnlySessionsCreated: Long = _readOnlySessionsCreated

  private[this] var _readWriteSessionsCreated = 0
  def readWriteSessionsCreated: Long = _readWriteSessionsCreated

  override def createReadOnlySession(handle: SlickDatabase) = {
    _readOnlySessionsCreated += 1
    super.createReadOnlySession(handle)
  }
  override def createReadWriteSession(handle: SlickDatabase) = {
    _readWriteSessionsCreated += 1
    super.createReadWriteSession(handle)
  }

  def doWithoutCreatingSessions[A](block: => A): A = {
    val (oldRO, oldRW) = (readOnlySessionsCreated, readWriteSessionsCreated)
    val result = block
    val (roCreated, rwCreated) = (readOnlySessionsCreated - oldRO, readWriteSessionsCreated - oldRW)
    if (roCreated != 0) throw new IllegalStateException(s"Created $roCreated read-only database sessions")
    if (rwCreated != 0) throw new IllegalStateException(s"Created $rwCreated read-write database sessions")
    result
  }
}
