package com.keepit.test

import scala.slick.session.ResultSetConcurrency
import com.google.inject.{Module, Injector}
import com.keepit.common.db.{TestDbInfo, DbInfo}
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import java.sql.{Driver, DriverManager}
import com.keepit.inject.EmptyInjector
import play.api.Mode
import com.keepit.common.actor.StandaloneTestActorSystemModule

@deprecated("Use SimpleTestInjector instead", "July 3rd 2013")
trait TestInjector extends EmptyInjector {
  val mode = Mode.Test
  val modules = Seq(TestModule(), StandaloneTestActorSystemModule())
}

@deprecated("Use SimpleTestDbRunner instead", "July 3rd 2013")
trait TestDBRunner extends TestInjector with DbRepos {

  def dbInfo: DbInfo = TestDbInfo.dbInfo
  DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))

  def withDB[T](additionalModules: Module*)(f: Injector => T) = {
    withCustomInjector(additionalModules: _*) { implicit injector =>
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
          val statment = s.replace("create table ", "create table IF NOT EXISTS ")
          try {
            session.withPreparedStatement(statment)(_.execute)
          } catch {
            case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}, statement: [$statment]", t)
          }
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}", t)
      }
    }
  }

}
