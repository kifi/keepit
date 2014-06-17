package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.{DbSequence, SequenceNumber, SequenceNumberRange, MySqlDatabaseDialect}
import scala.slick.driver.MySQLDriver
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}
import com.keepit.common.logging.Logging

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class MySQL(val masterDb: SlickDatabase, val slaveDb: Option[SlickDatabase])
    extends DataBaseComponent with Logging {
  println("initiating MySQL driver")
  val Driver = MySQLDriver
  val dialect = MySqlDatabaseDialect

  def getSequence[T](name: String): DbSequence[T] = new DbSequence[T](name) {
    def incrementAndGet()(implicit sess: RWSession): SequenceNumber[T] = {
      val ts = System.currentTimeMillis()
      sess.getPreparedStatement(s"UPDATE $name SET id=LAST_INSERT_ID(id+1);").execute()
      val rs = sess.getPreparedStatement(s"SELECT LAST_INSERT_ID();").executeQuery()
      rs.next()
      val res = SequenceNumber[T](rs.getLong(1))
      val lapsed = System.currentTimeMillis - ts
      if (lapsed > 5000) { // may add airbrake later
        val msg = s"incrementAndGet($name) takes too long ($lapsed ms) res=$res"
        log.error(msg, new IllegalStateException(msg))
      }
      res
    }

    def getLastGeneratedSeq()(implicit session: RSession): SequenceNumber[T] = {
      val rs = session.getPreparedStatement(s"SELECT id from $name;").executeQuery()
      rs.next()
      SequenceNumber[T](rs.getLong(1))
    }

    def reserve(n: Int)(implicit sess: RWSession): SequenceNumberRange[T] = {
      if (n > 0) {
        val ts = System.currentTimeMillis()
        val stmt = sess.getPreparedStatement(s"UPDATE $name SET id=LAST_INSERT_ID(id+?);")
        stmt.setInt(1, n)
        stmt.execute()
        val rs = sess.getPreparedStatement(s"SELECT LAST_INSERT_ID();").executeQuery()
        rs.next()
        val end = rs.getLong(1)
        val start = end + 1 - n
        val res = SequenceNumberRange[T](start, end)
        val lapsed = System.currentTimeMillis - ts
        if (lapsed > 5000) { // may add airbrake later
        val msg = s"nextRange($name) takes too long ($lapsed ms) res=$res"
          log.error(msg, new IllegalStateException(msg))
        }
        res
      } else {
        throw new IllegalArgumentException("non-positive size is specified")
      }
    }
  }
}

object MySQL {
  val driverName = "com.mysql.jdbc.Driver"
  val MAX_ROW_LIMIT = 50000000
}

