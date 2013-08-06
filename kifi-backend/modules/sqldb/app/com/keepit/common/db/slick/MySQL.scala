package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.{DbSequence, SequenceNumber, MySqlDatabaseDialect}
import scala.slick.driver.MySQLDriver
import scala.slick.session.{Database => SlickDatabase}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class MySQL(val masterDb: SlickDatabase, val slaveDb: Option[SlickDatabase])
    extends DataBaseComponent {
  println("initiating MySQL driver")
  val Driver = MySQLDriver
  val dialect = MySqlDatabaseDialect

  def getSequence(name: String): DbSequence = new DbSequence(name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber = {
      sess.getPreparedStatement(s"UPDATE $name SET id=LAST_INSERT_ID(id+1);").execute()
      val rs = sess.getPreparedStatement(s"SELECT LAST_INSERT_ID();").executeQuery()
      rs.next()
      SequenceNumber(rs.getLong(1))
    }
  }
}

object MySQL {
  val driverName = "com.mysql.jdbc.Driver"
}

