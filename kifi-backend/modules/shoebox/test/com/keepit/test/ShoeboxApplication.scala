package com.keepit.test

import play.api.{Application, Mode}
import com.keepit.inject.{TestFortyTwoModule, ApplicationInjector, EmptyInjector}
import com.keepit.common.db.{TestDbInfo}
import java.sql.{Driver, DriverManager}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RWSession
import scala.slick.session.ResultSetConcurrency
import java.io.File
import play.utils.Threads
import com.keepit.common.time.FakeClockModule
import scala.Some
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.google.inject.util.Modules
import com.google.inject.{Injector, Module}
import com.keepit.common.cache.{HashMapMemoryCacheModule, ShoeboxCacheModule}
import com.keepit.common.zookeeper.FakeDiscoveryModule

class TestGlobalWithDB(defaultModules: Seq[Module], overridingModules: Seq[Module])
  extends TestGlobal(defaultModules, overridingModules) {

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

class ShoeboxApplication(overridingModules: Module*)(implicit path: File = new File("./modules/shoebox/"))
  extends TestApplicationFromGlobal(path, new TestGlobalWithDB(
    Seq(
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      TestSlickModule(TestDbInfo.dbInfo),
      ShoeboxCacheModule(HashMapMemoryCacheModule())
    ), overridingModules
  ))

trait ShoeboxApplicationInjector extends ApplicationInjector with ShoeboxInjectionHelpers

trait ShoeboxTestInjector extends EmptyInjector with ShoeboxInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule(), TestSlickModule(TestDbInfo.dbInfo), ShoeboxCacheModule(HashMapMemoryCacheModule()))

  def dbInfo: DbInfo = TestDbInfo.dbInfo
  DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))

  def withDb[T](overridingModules: Module*)(f: Injector => T) = {
    withCustomInjector(overridingModules:_*) { implicit injector =>
      val h2 = inject[DataBaseComponent].asInstanceOf[H2]
      h2.initListener = Some(new TableInitListener {
        def init(table: TableWithDDL) = executeTableDDL(h2, table)
        def initSequence(sequence: String) = executeSequenceinit(h2, sequence)
      })
      try {
        f(injector)
      } finally {
        readWrite(h2) { implicit session =>
          val conn = session.conn
          // conn.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE")
          // h2.tablesToInit.values foreach { table =>
          //   conn.createStatement().execute(s"TRUNCATE TABLE ${table.tableName}")
          // }
          // conn.createStatement().execute("SET REFERENTIAL_INTEGRITY TRUE")
          // h2.sequencesToInit.values foreach { sequence =>
          //   conn.createStatement().execute(s"DROP SEQUENCE IF EXISTS $sequence")
          // }
          conn.createStatement().execute("DROP ALL OBJECTS")
        }
      }
    }
  }

  private def readWrite[T](db: H2)(f: RWSession => T) = {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try {
      s.withTransaction {
        f(new RWSession(s))
      }
    } finally s.close()
  }

  def executeSequenceinit(db: H2, sequence: String): Unit = {
    println(s"initiating sequence [$sequence]")
    readWrite(db) { implicit session =>
      try {
        val statment = s"CREATE SEQUENCE IF NOT EXISTS $sequence;"
        try {
          session.withPreparedStatement(statment)(_.execute)
        } catch {
          case t: Throwable => throw new Exception(s"fail initiating sequence $sequence, statement: [$statment]", t)
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating table $sequence", t)
      }
    }
  }

  def executeTableDDL(db: H2, table: TableWithDDL): Unit = {
    println(s"initiating table [${table.tableName}]")
    readWrite(db) { implicit session =>
      try {
        val ddl = table.ddl
        for (s <- ddl.createStatements) {
          val statement = s.replace("create table ", "create table IF NOT EXISTS ")
          try {
            session.withPreparedStatement(statement)(_.execute)
          } catch {
            case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}, statement: [$statement]", t)
          }
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}", t)
      }
    }
  }
}
