package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.{SequenceNumberRange, DbSequence, SequenceNumber, H2DatabaseDialect}
import scala.slick.driver.H2Driver
import scala.collection.concurrent.TrieMap
import scala.slick.driver.JdbcDriver.DDL
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}
import com.keepit.common.logging.Logging

trait TableInitListener {
  def init(tableName: String, ddl: { def createStatements: Iterator[String] }): Unit
  def initSequence(sequence: String): Unit
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val masterDb: SlickDatabase, val slaveDb: Option[SlickDatabase])
    extends DataBaseComponent with Logging {

  val Driver = H2Driver
  val tablesToInit = new TrieMap[String, { def createStatements: Iterator[String] }]
  val sequencesToInit = new TrieMap[String, String]
  var initListener: Option[TableInitListener] = None

  //first initiation of the table if they where loaded staticly by the injector before the db was initiated
  tablesToInit foreach { case (tableName, ddl) => initTableNow(tableName, ddl) }
  val dialect = H2DatabaseDialect

  def getSequence[T](name: String): DbSequence[T] = new DbSequence[T](name) {
    initSequence(name)
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber[T] = {
      val stmt = sess.conn.prepareStatement(s"""SELECT NEXTVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber[T](rs.getLong(1))
    }

    def getLastGeneratedSeq()(implicit session: RSession): SequenceNumber[T] = {
      val stmt = session.conn.prepareStatement(s"""SELECT CURRVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber[T](rs.getLong(1))
    }

    def reserve(n: Int)(implicit session: RWSession): SequenceNumberRange[T] = {
      if (n > 0) {
        val start = incrementAndGet()
        for (i <- 1 until n) { incrementAndGet() }
        val end = getLastGeneratedSeq()
        SequenceNumberRange[T](start.value, end.value)
      } else {
        throw new IllegalArgumentException("non-positive size is specified")
      }
    }
  }

  /**
   * The toUpperCase is per an H2 "bug?"
   * http://stackoverflow.com/a/8722814/81698
   */
  override def entityName(name: String): String = name.toUpperCase()

  private def initSequence(sequence: String) {
    if (!sequencesToInit.contains(sequence)) {
      sequencesToInit(sequence) = sequence
      //after the db has been initiated we would like to initiate sequences as they come through
      initSequenceNow(sequence)
    }
  }

  private def initSequenceNow(sequence: String) = initListener map {listener =>
    listener.initSequence(sequence)
  }

  override def initTable(tableName: String, ddl: { def createStatements: Iterator[String] }): Unit = {
    if (!tablesToInit.contains(tableName)) {
      tablesToInit(tableName) = ddl
      //after the db has been initiated we would like to initiate tables as they come through
      initTableNow(tableName, ddl)
    }
  }

  private def initTableNow(tableName: String, ddl: { def createStatements: Iterator[String] }) = initListener map {listener =>
    listener.init(tableName, ddl)
  }

}

object H2 {
  val driverName = "org.h2.Driver"
}

