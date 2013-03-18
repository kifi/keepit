package com.keepit.test

import scala.slick.testutil.TestDBs
import java.util.concurrent.atomic.AtomicLong

trait TestDBRunner {
  val testDB: TestDB = TestDBs.H2Mem

  private lazy val dbInstance = testDB.createDB()

  def withDB[T](f: Database => T) = {
    testDB.cleanUpBefore()
    try {
      f(new Database(new DataBaseComponent(dbInstance)))
    } finally {
      testDB.cleanUpAfter()
    }
  }
}

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class TestDBComponent(val testDB: TestDB)
    extends DataBaseComponent {
  println("initiating TestDB driver")
  val Driver = testDB
  private lazy val dbSequence = new AtomicLong(0)

  def getSequence(name: String): DbSequence = DbSequence(dbSequence.incrementAndGet)

  override def entityName(name: String): String = name.toUpperCase()
}

