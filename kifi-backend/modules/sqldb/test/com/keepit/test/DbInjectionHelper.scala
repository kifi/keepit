package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.{SlickSessionProvider, Database}
import com.keepit.model._
import com.keepit.common.db.TestSlickSessionProvider
import com.google.inject.Injector
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.TestDbInfo
import com.google.inject.{Injector, Module}
import scala.slick.session.ResultSetConcurrency
import java.sql.{Driver, DriverManager}
import com.keepit.common.logging.Logging

trait DbInjectionHelper extends Logging { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]

  def dbInfo: DbInfo = TestDbInfo.dbInfo
  DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))

  def withDb[T](overridingModules: Module*)(f: Injector => T) = {
    withInjector(overridingModules:_*) { implicit injector =>
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
    val s = db.masterDb.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try {
      s.withTransaction {
        f(new RWSession(s))
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

  def executeTableDDL(db: H2, table: TableWithDDL): Unit = {
    log.debug(s"initiating table [${table.tableName}]")
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
