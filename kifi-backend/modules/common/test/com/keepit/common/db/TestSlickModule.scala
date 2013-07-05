package com.keepit.common.db

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick.{DbExecutionContext, H2, SlickSessionProvider, SlickSessionProviderImpl}
import scala.slick.session.{Database => SlickDatabase}
import scala.concurrent.ExecutionContext.Implicits.{global => globalExecutionContext}

case class TestSlickModule(dbInfo: DbInfo) extends SlickModule(dbInfo) {

  @Provides @Singleton
  def slickSessionProvider: SlickSessionProvider = TestSlickSessionProvider()

  @Provides @Singleton
  def dbExecutionContextProvider: DbExecutionContext = DbExecutionContext(globalExecutionContext)

}

case class TestSlickSessionProvider() extends SlickSessionProviderImpl {

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

object TestDbInfo {
  val url = "jdbc:h2:mem:shoebox;USER=shoebox;MODE=MYSQL;MVCC=TRUE;DB_CLOSE_DELAY=-1"
  val dbInfo = new DbInfo() {
    //later on we can customize it by the application name
    lazy val database = SlickDatabase.forURL(url = url)
    lazy val driverName = H2.driverName
    //    lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
    //    lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
  }
}