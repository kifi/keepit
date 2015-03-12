package com.keepit.test

import java.sql.{ Driver, DriverManager }
import java.util.concurrent.atomic.AtomicBoolean

import com.google.inject.{ Injector, Module }
import com.keepit.common.db.TestDbInfo
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.macros.Location

import scala.collection.mutable.ListBuffer
import scala.slick.driver.JdbcDriver.simple.{ Database => SlickDatabase }
import scala.slick.jdbc.ResultSetConcurrency

trait DbInjectionHelper extends Logging { self: InjectorProvider =>

  def db(implicit injector: Injector) = inject[Database]

  def dbInfo: DbInfo = TestDbInfo.dbInfo
  DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))

  def withDb[T](overridingModules: Module*)(f: Injector => T) = {
    val inScope = new AtomicBoolean(true)
    withInjector(overridingModules: _*) { implicit injector =>
      val h2 = inject[DataBaseComponent].asInstanceOf[H2]
      val initiatedTables = ListBuffer[String]()
      h2.initListener = Some(new TableInitListener {
        def init(tableName: String, ddl: { def createStatements: Iterator[String] }) = {
          if (!inScope.get()) throw new Exception(s"[${getClass.getCanonicalName}] Initiating table [$tableName] when test scope is closed!")
          initiatedTables += tableName.toUpperCase
          executeTableDDL(h2, tableName, ddl)
        }
        def initSequence(sequence: String) = executeSequenceinit(h2, sequence)
      })
      try {
        f(injector)
      } finally {
        inScope.set(false)
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
          val rows = conn.createStatement().executeQuery("show tables")
          val tables = ListBuffer[String]()
          while (rows.next()) {
            val table = rows.getString(1)
            tables += table.toUpperCase
          }
          if (initiatedTables.size != tables.size) println(s"[${getClass.getCanonicalName}] initiated ${initiatedTables.size} tables but dropping ${tables.size} tables! Init: [${initiatedTables.mkString(", ")}], Dropping: [${tables.mkString(", ")}]")
          //          println(s"[${getClass.getCanonicalName}] Dropping ${tables.size} tables [${tables.mkString(", ")}]")
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

  private[test] def executeSequenceinit(db: H2, sequence: String): Unit = {
    //    println(s"[${getClass.getCanonicalName}] initiating sequence [$sequence]")
    readWrite(db) { implicit session =>
      try {
        val statment = s"CREATE SEQUENCE IF NOT EXISTS $sequence;"
        try {
          session.withPreparedStatement(statment)(_.execute)
        } catch {
          case t: Throwable => throw new Exception(s"fail initiating sequence $sequence, statement: [$statment]", t)
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating sequence $sequence", t)
      }
    }
  }

  private[test] def executeTableDDL(db: H2, tableName: String, ddl: { def createStatements: Iterator[String] }): Unit = {
    //    println(s"[${getClass.getCanonicalName}] initiating table [$tableName]")
    readWrite(db) { implicit session =>
      try {
        for (s <- ddl.createStatements) {
          val statement = s.replace("create table ", "create table IF NOT EXISTS ")
          try {
            session.withPreparedStatement(statement)(_.execute)
          } catch {
            case t: Throwable => throw new Exception(s"[${getClass.getCanonicalName}] fail initiating table $tableName, statement: [$statement]", t)
          }
        }
      } catch {
        case t: Throwable => throw new Exception(s"[${getClass.getCanonicalName}] fail initiating table $tableName}", t)
      }
    }
    //    println(s"[${getClass.getCanonicalName}] initiated table [$tableName]")
  }
}
