package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, DbInfo}
import scala.slick.driver.MySQLDriver

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class MySQL(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating MySQL driver")
  val Driver = MySQLDriver

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      val stmt = sess.getPreparedStatement(s"""UPDATE $name SET id=LAST_INSERT_ID(id+1); SELECT LAST_INSERT_ID();""")
      val rs = stmt.executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }
  }
}



object MySQL {
  val driverName = "com.mysql.jdbc.Driver"
}

