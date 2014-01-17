package com.keepit.common.db

import com.keepit.test._
import com.keepit.common.db.slick._
import org.specs2.mutable._
import com.keepit.common.healthcheck._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.SQLException
import com.google.inject.Injector

class DataBaseComponentTest extends Specification with DbTestInjector {

  "Session" should {

    "not create real sessions if not used" in {
      withDb() { implicit injector: Injector =>
        inject[TestSlickSessionProvider].doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            1 === 1
          }
        }
        inject[TestSlickSessionProvider].doWithoutCreatingSessions {
          db.readWrite { implicit s =>
            1 === 1
          }
        }
      }
    }
/*
    "execute all read-write sessions inside a transaction" in {
      withDb() { implicit injector: Injector =>
        db.readWrite { implicit s =>
          s.conn.getAutoCommit must beFalse
        }
      }
    }

    "not be executed inside another session: rw->ro" in {
      withDb() { implicit injector: Injector =>
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readWrite { implicit s1 =>
          (inject[Database].readOnly { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: rw->rw" in {
      withDb() { implicit injector: Injector =>
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readWrite { implicit s1 =>
          (inject[Database].readWrite { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: ro->ro" in {
      withDb() { implicit injector: Injector =>
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readOnly { implicit s1 =>
          (inject[Database].readOnly { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }

    "not be executed inside another session: ro->rw" in {
      withDb() { implicit injector: Injector =>
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0
        inject[Database].readOnly { implicit s1 =>
          (inject[Database].readWrite { implicit s2 => 1 === 1 }) must throwA[InSessionException]
        }
        //fakeHealthcheck.errorCount() === 1
      }
    }*/

    "attempt retry" in {
      withDb() { implicit injector: Injector =>
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
      withDb() { implicit injector: Injector =>
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
      withDb() { implicit injector: Injector =>
        var counter = 0
        (inject[Database].readWrite(attempts = 3) { implicit s1 =>
          counter = counter + 1
          if (1 == 1) throw new MySQLIntegrityConstraintViolationException("i'm really bad")
          true
        }) must throwA[MySQLIntegrityConstraintViolationException]
        counter === 1
      }
    }

    "do batch update using readWriteBatch" in {
      withDb() { implicit injector: Injector =>
        val result = (inject[Database].readWriteBatch(Seq(0, 1, 2, 3, 4)) { (s, i) =>
          if (i == 2) throw new SQLException("i'm bad")
          true
        })

        result.size === 5
        result(0).isSuccess === true
        result(1).isSuccess === true
        result(2).get must throwA[SQLException]
        result(3).get must throwA[ExecutionSkipped]
        result(4).get must throwA[ExecutionSkipped]
      }
    }

    "attempt retry batch update using readWriteBatch" in {
      withDb() { implicit injector: Injector =>
        var willSuccess = Array(false, false, false, false, false)
        var success = Array(false, false, false, false, false)
        def exec(attempts: Int) = (inject[Database].readWriteBatch(Seq(0, 1, 2, 3, 4), attempts) { (s, i) =>
          if (!willSuccess(i)) {
            willSuccess(i) = true // will succeed next time
            throw new SQLException("i'm bad")
          }
          success(i) = true
          true
        })

        var result = exec(3)
        result.size === 5
        success.count(flag => flag) === 2
        (0 until 5).foreach{ i => result(i).isSuccess === success(i) }

        willSuccess = Array(false, false, false, false, false)
        success = Array(false, false, false, false, false)
        result = exec(5)
        result.size === 5
        success.count(flag => flag) === 4
        (0 until 5).foreach{ i => result(i).isSuccess === success(i) }
      }
    }

    "attempt retry batch update using readWriteBatch (with MySQLIntegrityConstraintViolationException)" in {
      withDb() { implicit injector: Injector =>
        var willSuccess = Array(false, false, false, false, false)
        var success = Array(false, false, false, false, false)
        def exec(attempts: Int) = (inject[Database].readWriteBatch(Seq(0, 1, 2, 3, 4), attempts) { (s, i) =>
          if (!willSuccess(i)) {
            willSuccess(i) = true // will succeed next time if not MySQLIntegrityConstraintViolationException
            throw new MySQLIntegrityConstraintViolationException("i'm really bad")
          }
          success(i) = true
          true
        })

        var result = exec(3)
        result.size === 5
        success.count(flag => flag) === 0
        (0 until 5).foreach{ i => result(i).isSuccess === success(i) }

        willSuccess = Array(false, false, false, false, false)
        success = Array(false, false, false, false, false)
        result = exec(5)
        result.size === 5
        success.count(flag => flag) === 0
        (0 until 5).foreach{ i => result(i).isSuccess === success(i) }
      }
    }
  }
}
