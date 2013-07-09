package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, H2DatabaseDialect}
import scala.slick.driver.H2Driver
import scala.collection.concurrent.TrieMap
import scala.slick.lifted.DDL

trait TableInitListener {
  def init(table: TableWithDDL): Unit
  def initSequence(sequence: String): Unit
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver
  val tablesToInit = new TrieMap[String, TableWithDDL]
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
  }

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

  override def initTable(table: TableWithDDL) {
    if (!tablesToInit.contains(table.tableName)) {
      tablesToInit(table.tableName) = table
      //after the db has been initiated we would like to initiate tables as they come through
      initTableNow(table)
    }
  }

  private def initTableNow(table: TableWithDDL) = initListener map {listener =>
    listener.init(table)
  }

}

object H2 {
  val driverName = "org.h2.Driver"
}

