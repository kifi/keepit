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
import com.keepit.common.healthcheck._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.SQLException

class DataBaseComponentTest extends Specification with ApplicationInjector {

  "Session" should {

    "not be executed inside another session: rw->ro" in {
      running(new DeprecatedEmptyApplication()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readWrite { implicit s1 =>
          (inject[Database].readOnly { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: rw->rw" in {
      running(new DeprecatedEmptyApplication()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readWrite { implicit s1 =>
          (inject[Database].readWrite { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: ro->ro" in {
      running(new DeprecatedEmptyApplication()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readOnly { implicit s1 =>
          (inject[Database].readOnly { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: ro->rw" in {
      running(new DeprecatedEmptyApplication()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readOnly { implicit s1 =>
          (inject[Database].readWrite { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "attempt retry" in {
      running(new DeprecatedEmptyApplication()) {
        var counter = 0
        (inject[Database].readWrite(attempts = 3) { implicit s1 =>
          counter = counter + 1
          if (1 == 1) throw new SQLException("i'm bad")
          true
        }) must throwA[SQLException]
        counter === 3
      }
    }

    "attempt retry not with regular exception" in {
      running(new DeprecatedEmptyApplication()) {
        var counter = 0
        (inject[Database].readWrite(attempts = 3) { implicit s1 =>
          counter = counter + 1
          if (1 == 1) throw new Exception("i'm not as bad")
          true
        }) must throwA[Exception]
        counter === 1
      }
    }

    "attempt retry not with MySQLIntegrityConstraintViolationException" in {
      running(new DeprecatedEmptyApplication()) {
        var counter = 0
        (inject[Database].readWrite(attempts = 3) { implicit s1 =>
          counter = counter + 1
          if (1 == 1) throw new MySQLIntegrityConstraintViolationException("i'm really bad")
          true
        }) must throwA[MySQLIntegrityConstraintViolationException]
        counter === 1
      }
    }


  }
}
