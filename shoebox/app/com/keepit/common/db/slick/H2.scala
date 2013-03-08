package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, DbInfo}
import scala.slick.driver.H2Driver

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      val stmt = sess.conn.prepareStatement(s"""SELECT NEXTVAL('$name')""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }
  }

  override def entityName(name: String): String = name.toUpperCase()
}

object H2 {
  val driverName = "org.h2.Driver"
}

