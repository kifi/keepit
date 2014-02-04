package com.keepit.common.db

import com.keepit.common.time._
import com.keepit.common.db.slick._
import org.specs2.mutable._
import org.joda.time._
import com.google.inject.Injector
import com.keepit.test._

class DatabaseDialectTest extends Specification with DbTestInjector {

  val dec_20_2013 = new DateTime(2013, 12, 20, 10, 1, 30, DEFAULT_DATE_TIME_ZONE)

  "MySqlDatabaseDialect" should {

    "stringToDay to string" in {
      MySqlDatabaseDialect.day(dec_20_2013) === """STR_TO_DATE('2013-12-20', '%Y-%m-%d')"""
    }
  }

  "H2DatabaseDialect" should {

    "stringToDay to string" in {
      H2DatabaseDialect.day(dec_20_2013) === """PARSEDATETIME('2013-12-20', 'y-M-d')"""
    }

    "stringToDay to db" in {
      withDb() { implicit injector: Injector =>
        inject[Database].readWrite { implicit s =>
          val st = s.conn.createStatement()
          val sql = s"""select DATEADD('MONTH', 1, ${H2DatabaseDialect.day(dec_20_2013)}) as day from dual"""
          sql === """select DATEADD('MONTH', 1, PARSEDATETIME('2013-12-20', 'y-M-d')) as day from dual"""
          val rs = st.executeQuery(sql)
          rs.next
          rs.getString("day").split(' ')(0) === "2014-01-20"
        }
      }
    }


    // H2 uses SimpleDateFormat which is *not* ISO-8601 compatible!
    "stringToDate to string" in {
      H2DatabaseDialect.dateTime(dec_20_2013) === """PARSEDATETIME('2013-12-20 10:01:30.000', 'yyyy-MM-d hh:mm:ss.SSS')"""
    }

    "stringToDay to db" in {
      withDb() { implicit injector: Injector =>
        inject[Database].readWrite { implicit s =>
          val st = s.conn.createStatement()
          val sql = s"""select DATEADD('MONTH', 1, ${H2DatabaseDialect.dateTime(dec_20_2013)}) as day from dual"""
          sql === """select DATEADD('MONTH', 1, PARSEDATETIME('2013-12-20 10:01:30.000', 'yyyy-MM-d hh:mm:ss.SSS')) as day from dual"""
          val rs = st.executeQuery(sql)
          rs.next
          rs.getString("day").split(' ')(0) === "2014-01-20"
        }
      }
    }

  }
}
