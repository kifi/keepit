package com.keepit.test

import java.sql.{Driver, DriverManager}

import com.google.inject.{Injector, Module}
import com.keepit.common.db.TestDbInfo
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.macros.Location

import scala.slick.driver.JdbcDriver.simple.{Database => SlickDatabase}
import scala.slick.jdbc.ResultSetConcurrency

trait DbInjectionHelper extends Logging { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]

  def dbInfo: DbInfo = TestDbInfo.dbInfo
  DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))

  def withDb[T](overridingModules: Module*)(f: Injector => T) = {
    withInjector(overridingModules: _*) { implicit injector =>
      val h2 = inject[DataBaseComponent].asInstanceOf[H2]
      h2.initListener = Some(new TableInitListener {
        def init(tableName: String, ddl: { def createStatements: Iterator[String] }) = executeTableDDL(h2, tableName, ddl)
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
    val s = db.masterDb.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try {
      s.withTransaction {
        f(new RWSession(s, Location.capture))
      }
    } finally s.close()
  }

  def executeSequenceinit(db: H2, sequence: String): Unit = {
    log.debug(s"initiating sequence [$sequence]")
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

  def executeTableDDL(db: H2, tableName: String, ddl: { def createStatements: Iterator[String] }): Unit = {
    log.debug(s"initiating table [$tableName]")
    readWrite(db) { implicit session =>
      try {
        for (s <- ddl.createStatements) {
          val statement = s.replace("create table ", "create table IF NOT EXISTS ")
          try {
            session.withPreparedStatement(statement)(_.execute)
          } catch {
            case t: Throwable => throw new Exception(s"fail initiating table $tableName, statement: [$statement]", t)
          }
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating table $tableName}", t)
      }
    }
  }
}
