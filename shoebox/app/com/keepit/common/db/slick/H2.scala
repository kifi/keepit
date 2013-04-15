package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, DbInfo}
import scala.slick.driver.H2Driver
import scala.collection.mutable.HashMap
import scala.slick.lifted.DDL

trait TableInitListener {
  def init(table: TableWithDDL): Unit
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver
  val tablesToInit = new HashMap[String, TableWithDDL]
  var initListener: Option[TableInitListener] = None

  //first initiation of the table if they where loaded staticly by the injector before the db was initiated
  tablesToInit.values foreach initTable

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      val stmt = sess.conn.prepareStatement(s"""SELECT NEXTVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }
  }

  override def entityName(name: String): String = name.toUpperCase()

  override def tableToInit(table: TableWithDDL) {
    if (!tablesToInit.contains(table.tableName)) {
      tablesToInit(table.tableName) = table
      //after the db has been initiated we would like to initiate tables as they come through
      initTable(table)
    }
  }

  private def initTable(table: TableWithDDL) = initListener map {listener =>
    listener.init(table)
  }

}

object H2 {
  val driverName = "org.h2.Driver"
}

