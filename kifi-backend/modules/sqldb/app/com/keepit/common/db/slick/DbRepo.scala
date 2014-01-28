package com.keepit.common.db.slick

import com.keepit.common.strings._
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.inject._
import org.joda.time.DateTime
import play.api.Play.current
import scala.slick.driver._
import scala.slick.session._
import scala.slick.lifted._
import DBSession._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.SQLException
import play.api.Logger
import scala.reflect.ClassTag

trait Repo[M <: Model[M]] {
  def get(id: Id[M])(implicit session: RSession): M
  def all()(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M]
  def invalidateCache(model: M)(implicit session: RSession): Unit
  def deleteCache(model: M)(implicit session: RSession): Unit
}

trait RepoWithDelete[M <: Model[M]] { self: Repo[M] =>
  def delete(model: M)(implicit session:RWSession):Int

  // potentially more efficient variant but we currently depend on having the model available for our caches
  // def deleteCacheById(id: Id[M]): Int
  // def deleteById(id: Id[M])(implicit ev$0:ClassTag[M], session:RWSession):Int
}

trait RepoWithExternalId[M <: ModelWithExternalId[M]] { self: Repo[M] =>
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait TableWithDDL {
  def ddl: DDL
  def tableName: String
}

trait DbRepo[M <: Model[M]] extends Repo[M] with DelayedInit {
  import FortyTwoTypeMappers._
  val db: DataBaseComponent
  val clock: Clock
  import db.Driver.Implicit._ // here's the driver, abstracted away
  import db.Driver.Table

  lazy val dbLog = Logger("com.keepit.db")

  implicit val idMapper = FortyTwoGenericTypeMappers.idMapper[M]
  implicit val stateTypeMapper = FortyTwoGenericTypeMappers.stateTypeMapper[M]

  protected def table: RepoTable[M]

  //we must call the init after the underlying constructor finish defining its ddl.
  def delayedInit(body: => Unit) = {
    body
    db.initTable(table)
  }

  def descTable(): String = db.masterDb.withSession {
    table.ddl.createStatements mkString "\n"
  }

  def save(model: M)(implicit session: RWSession): M = try {
    val toUpdate = model.withUpdateTime(clock.now)
    val result = model.id match {
      case Some(id) => update(toUpdate)
      case None => toUpdate.withId(insert(toUpdate))
    }
    model match {
      case m: ModelWithState[M] if m.state == State[M]("inactive") => deleteCache(result)
      case _ => invalidateCache(result)
    }
    result
  } catch {
    case m: MySQLIntegrityConstraintViolationException =>
      throw new MySQLIntegrityConstraintViolationException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}").initCause(m)
    case t: SQLException => throw new SQLException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}", t)
  }

  def count(implicit session: RSession): Int = Query(table.length).first

  def get(id: Id[M])(implicit session: RSession): M = {
    val startTime = System.currentTimeMillis()
    val model = (for(f <- table if f.id is id) yield f).first
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:GET\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    model
  }

  def all()(implicit session: RSession): Seq[M] = table.map(t => t).list

  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M] =  {
    val q = for {
      t <- table if !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  private def insert(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val inserted = table.autoInc.insert(model)
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:INSERT\tduration:${time}\tmodel:${inserted.getClass.getSimpleName()}\tmodel:${inserted.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    inserted
  }

  private def update(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val target = for(t <- table if t.id === model.id.get) yield t
    val count = target.update(model)
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:UPDATE\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    if (count != 1) {
      deleteCache(model)
      throw new IllegalStateException(s"Updating $count models of [${model.toString.abbreviate(200).trimAndRemoveLineBreaks}] instead of exactly one. Maybe there is a cache issue. The actual model (from cache) is no longer in db.")
    }
    model
  }

  abstract class RepoTable[M <: Model[M]](db: DataBaseComponent, name: String) extends Table[M](db.entityName(name)) with TableWithDDL {

    implicit val idMapper = FortyTwoGenericTypeMappers.idMapper[M]
    implicit val stateTypeMapper = FortyTwoGenericTypeMappers.stateTypeMapper[M]

    //standardizing the following columns for all entities
    def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)
    def createdAt = column[DateTime]("created_at", O.NotNull)
    def updatedAt = column[DateTime]("updated_at", O.NotNull)
    //state may not exist in all entities, if it does then its column name is standardized as well.
    def state = column[State[M]]("state", O.NotNull)

    def autoInc = * returning id

    //H2 likes its column names in upper case where mysql does not mind.
    //the db component should figure it out
    override def column[C : TypeMapper](name: String, options: ColumnOption[C]*) =
      super.column(db.entityName(name), options:_*)
  }

  trait ExternalIdColumn[M <: ModelWithExternalId[M]] extends RepoTable[M] {
    implicit val externalIdMapper = new BaseTypeMapper[ExternalId[M]] {
      def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[M](profile)
    }

    def externalId = column[ExternalId[M]]("external_id", O.NotNull)
  }

  trait NamedColumns { self: RepoTable[_] =>
    lazy val _columnStrings = {
      create_*.map(_.name).take(30).map(db.entityName)
    }
    def columnStrings(tableName: String = tableName) = _columnStrings.map(c => tableName + "." + c).mkString(", ")
  }
}

trait DbRepoWithDelete[M <: Model[M]] extends RepoWithDelete[M] { self:DbRepo[M] =>
  import db.Driver.Implicit._

  def delete(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val target = (for(t <- table if t.id === model.id.get) yield t)
    val count = target.delete
    deleteCache(model)
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:DELETE\tduration:${time}\ttype:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    count
  }
}

trait ExternalIdColumnFunction[M <: ModelWithExternalId[M]] {
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait ExternalIdColumnDbFunction[M <: ModelWithExternalId[M]] extends RepoWithExternalId[M] { self: DbRepo[M] =>
  import db.Driver.Implicit._
  protected def externalIdColumn: ExternalIdColumn[M] = table.asInstanceOf[ExternalIdColumn[M]]

  implicit val ExternalIdMapperDelegate = new BaseTypeMapper[ExternalId[M]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[M](profile)
  }

  def get(id: ExternalId[M])(implicit session: RSession): M = getOpt(id).get

  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M] = {
    val startTime = System.currentTimeMillis()
    val model = (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:GET-EXT\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    model
  }
}
