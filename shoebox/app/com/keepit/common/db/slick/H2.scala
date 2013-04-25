package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, DbInfo, H2DatabaseDialect}
import scala.slick.driver.H2Driver
import scala.collection.concurrent.TrieMap
import scala.slick.lifted.DDL

trait TableInitListener {
  def init(table: TableWithDDL): Unit
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver
  val tablesToInit = new TrieMap[String, TableWithDDL]
  var initListener: Option[TableInitListener] = None

  //first initiation of the table if they where loaded staticly by the injector before the db was initiated
  tablesToInit.values foreach initTableNow
  val dialect = H2DatabaseDialect

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      val stmt = sess.conn.prepareStatement(s"""SELECT NEXTVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }
  }

  override def entityName(name: String): String = name.toUpperCase()

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

