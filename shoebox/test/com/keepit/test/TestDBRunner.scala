package com.keepit.test

import scala.slick.session.{ Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.DbInfo
import com.google.inject.util.Modules
import scala.slick.lifted.DDL
import com.google.inject.{Module, Injector}
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.model._
import com.keepit.inject.RichInjector

trait TestDBRunner extends TestInjector {

  def db(implicit injector: RichInjector): Database = inject[Database]

  def userRepo(implicit injector: RichInjector) = inject[UserRepo]
  def keepToCollectionRepo(implicit injector: RichInjector) = inject[KeepToCollectionRepo]
  def collectionRepo(implicit injector: RichInjector) = inject[CollectionRepo]
  def uriRepo(implicit injector: RichInjector) = inject[NormalizedURIRepo]
  def urlRepo(implicit injector: RichInjector) = inject[URLRepo]
  def bookmarkRepo(implicit injector: RichInjector) = inject[BookmarkRepo]
  def socialUserInfoRepo(implicit injector: RichInjector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: RichInjector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: RichInjector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: RichInjector) = inject[EmailAddressRepo]
  def unscrapableRepo(implicit injector: RichInjector) = inject[UnscrapableRepo]
  def electronicMailRepo(implicit injector: RichInjector) = inject[ElectronicMailRepo]

  def withDB[T](overridingModules: Module*)(f: RichInjector => T) = {
    withInjector(overridingModules: _*) { implicit injector =>
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
