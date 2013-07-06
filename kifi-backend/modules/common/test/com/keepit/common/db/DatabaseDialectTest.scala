package com.keepit.common.db

import java.util.UUID
import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.collection.mutable.{Map => MutableMap}
import org.joda.time._

class DatabaseDialectTest extends Specification with ApplicationInjector {

  val dec_20_2013 = new DateTime(2013, 12, 20, 0, 0, 0, zones.PT)

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
      running(new DeprecatedEmptyApplication()) {
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

  }
}
