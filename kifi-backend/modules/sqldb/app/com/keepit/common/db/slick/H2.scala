package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.{DbSequence, SequenceNumber, H2DatabaseDialect}
import scala.slick.driver.H2Driver
import scala.collection.concurrent.TrieMap
import scala.slick.driver.JdbcDriver.DDL
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}
import com.keepit.common.logging.Logging

trait TableInitListener {
  def init(repo: DbRepo[_]): Unit
  def initSequence(sequence: String): Unit
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val masterDb: SlickDatabase, val slaveDb: Option[SlickDatabase])
    extends DataBaseComponent with Logging {
  println("initiating H2 driver")
  val Driver = H2Driver
  val tablesToInit = new TrieMap[String, DbRepo[_]]
  val sequencesToInit = new TrieMap[String, String]
  var initListener: Option[TableInitListener] = None

  //first initiation of the table if they where loaded staticly by the injector before the db was initiated
  tablesToInit.values foreach initTableNow
  val dialect = H2DatabaseDialect

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    initSequence(name)
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      val stmt = sess.conn.prepareStatement(s"""SELECT NEXTVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }

    def getLastGeneratedSeq()(implicit session: RSession): SequenceNumber = {
      val stmt = session.conn.prepareStatement(s"""SELECT CURRVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
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

  override def initTable(repo: DbRepo[_]) {
    if (!tablesToInit.contains(repo._taggedTable.tableName)) {
      tablesToInit(repo._taggedTable.tableName) = repo
      //after the db has been initiated we would like to initiate tables as they come through
      initTableNow(repo)
    }
  }

  private def initTableNow(repo: DbRepo[_]) = initListener map {listener =>
    listener.init(repo)
  }

}

object H2 {
  val driverName = "org.h2.Driver"
}

