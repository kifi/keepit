package com.keepit.test

import com.google.inject.Injector
import scala.slick.session.{ Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import com.keepit.common.db.DbInfo
import com.google.inject.Guice
import com.keepit.common.db.slick.H2
import com.google.inject.Stage
import com.google.inject.util.Modules
import scala.slick.lifted.DDL
import com.keepit.inject.RichInjector
import com.keepit.common.db.slick.Database
import com.google.inject.Module
import com.keepit.inject.RichInjector
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.TableInitListener
import com.keepit.common.db.slick.TableWithDDL
import com.keepit.model.UserRepo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.UnscrapableRepo
import com.keepit.model.URLRepo
import com.keepit.model.EmailAddressRepo
import com.keepit.model.KifiInstallationRepo
import com.keepit.model.NormalizedURIRepo
import com.keepit.model.BookmarkRepo
import com.keepit.model.UserExperimentRepo
import com.keepit.model.UserRepoImpl

trait TestDBRunner extends TestInjector {

  def db(implicit injector: RichInjector): Database = inject[Database]

  def userRepo(implicit injector: RichInjector) = inject[UserRepo]
  def uriRepo(implicit injector: RichInjector) = inject[NormalizedURIRepo]
  def urlRepo(implicit injector: RichInjector) = inject[URLRepo]
  def bookmarkRepo(implicit injector: RichInjector) = inject[BookmarkRepo]
  def socialUserInfoRepo(implicit injector: RichInjector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: RichInjector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: RichInjector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: RichInjector) = inject[EmailAddressRepo]
  def unscrapableRepo(implicit injector: RichInjector) = inject[UnscrapableRepo]

  def withDB[T](overrideingModules: Module*)(f: RichInjector => T) = {
    withInjector(overrideingModules: _*) { implicit injector =>
      val db = inject[DataBaseComponent]
      val h2 = inject[DataBaseComponent].asInstanceOf[H2]
      h2.initListener = Some(new TableInitListener {
        def init(table: TableWithDDL) = initTable(db, table)
      })
      try {
        (f(injector))
      } finally {
        readWrite(db) { implicit session =>
          val conn = session.conn
//          conn.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE")
//          h2.tablesToInit.values foreach { table =>
//            conn.createStatement().execute(s"TRUNCATE TABLE ${table.tableName}")
//          }
//          conn.createStatement().execute("SET REFERENTIAL_INTEGRITY TRUE")
          conn.createStatement().execute("DROP ALL OBJECTS")
        }
      }
    }
  }

  private def readWrite[T](db: DataBaseComponent)(f: RWSession => T) = {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try {
      s.withTransaction {
        f(new RWSession(s))
      }
    } finally s.close()
  }

  def initTable(db: DataBaseComponent, table: TableWithDDL): Unit = {
    println(s"initiating table [${table.tableName}]")
    readWrite(db) { implicit session =>
      try {
        val ddl = table.ddl
        for (s <- ddl.createStatements) {
          try {
            session.withPreparedStatement(s)(_.execute)
          } catch {
            case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}, statement: [$s]", t)
          }
        }
      } catch {
        case t: Throwable => throw new Exception(s"fail initiating table ${table.tableName}", t)
      }
    }
  }
}
