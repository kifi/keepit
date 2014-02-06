package com.keepit.common.db.slick

import com.keepit.common.strings._
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import DBSession._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.{Timestamp, SQLException}
import play.api.Logger
import scala.slick.driver.JdbcDriver.DDL
import scala.slick.ast.{TypedType, ColumnOption}
import scala.slick.driver.{JdbcDriver, JdbcProfile, H2Driver, SQLiteDriver}

import scala.slick.lifted._
import scala.slick.driver.JdbcDriver.simple._
import scala.slick.lifted.Tag
import scala.slick.lifted.TableQuery
import scala.slick.lifted.Query
import com.keepit.common.logging.Logging


trait Repo[M <: Model[M]] {
  def get(id: Id[M])(implicit session: RSession): M
  def all()(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M]
  def invalidateCache(model: M)(implicit session: RSession): Unit
  def deleteCache(model: M)(implicit session: RSession): Unit
}

trait TableWithDDL {
  def ddl: DDL
  def tableName: String
}

trait DbRepo[M <: Model[M]] extends Repo[M] with FortyTwoGenericTypeMappers with Logging {
  val db: DataBaseComponent
  val clock: Clock
  val profile = db.Driver.profile

  import scala.slick.driver.JdbcDriver.Table
  //import db.Driver.simple._

  lazy val dbLog = Logger("com.keepit.db")


  // In general, when needed, use `import FortyTwoGenericTypeMappers._` and you'll be fine.
  // Listed explicitly here so subclasses can use it without an import.
  implicit val selfFdMapper = FortyTwoGenericTypeMappers.idMapper[M]
  implicit val selfStateMapper = FortyTwoGenericTypeMappers.stateTypeMapper[M]


  type RepoImpl <: RepoTable[M]
  def table(tag: Tag): RepoImpl
  lazy val _taggedTable = table(new BaseTag { base =>
    def taggedAs(path: List[scala.slick.ast.Symbol]): AbstractTable[_] = base.taggedAs(path)
  })
  val rows: TableQuery[RepoImpl] = TableQuery(table)
  val ddl = rows.ddl

  db.initTable(this)

  def descTable(): String = db.masterDb.withSession { session =>
    ddl.createStatements mkString "\n"
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
      session.directCacheAccess{
        deleteCache(model)
      }
      throw new MySQLIntegrityConstraintViolationException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}").initCause(m)
    case t: SQLException => throw new SQLException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}", t)
  }

  def count(implicit session: RSession): Int = Query(rows.length).first

  def get(id: Id[M])(implicit session: RSession): M = {
    val startTime = System.currentTimeMillis()
    val model = (for(f <- rows if f.id is id) yield f).first
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:GET\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    model
  }

  def all()(implicit session: RSession): Seq[M] = rows.list()

  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M] =  {
    val q = for {
      t <- rows if !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  private def insert(model: M)(implicit session: RWSession): Id[M] = {
    val startTime = System.currentTimeMillis()
    val inserted = (rows returning rows.map(_.id)) += model
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:INSERT\tduration:${time}\tmodel:${inserted.getClass.getSimpleName()}\tmodel:${inserted.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    inserted
  }

  private def update(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val target = for(t <- rows if t.id === model.id.get) yield t
    val count = target.update(model)
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:UPDATE\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    if (count != 1) {
      deleteCache(model)
      throw new IllegalStateException(s"Updating $count models of [${model.toString.abbreviate(200).trimAndRemoveLineBreaks}] instead of exactly one. Maybe there is a cache issue. The actual model (from cache) is no longer in db.")
    }
    model
  }

  abstract class RepoTable[M <: Model[M]](db: DataBaseComponent, tag: Tag, name: String) extends Table[M](tag: Tag, db.entityName(name))  with FortyTwoGenericTypeMappers with Logging {
    //standardizing the following columns for all entities
    def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)
    def createdAt = column[DateTime]("created_at", O.NotNull)
    def updatedAt = column[DateTime]("updated_at", O.NotNull)
    //state may not exist in all entities, if it does then its column name is standardized as well.
    def state = column[State[M]]("state", O.NotNull)

    def autoInc = this returning Query(id)

    //H2 likes its column names in upper case where mysql does not mind.
    //the db component should figure it out
    override def column[C](name: String, options: ColumnOption[C]*)(implicit tm: TypedType[C]) =
      super.column(db.entityName(name), options:_*)
  }

  trait ExternalIdColumn[M <: ModelWithExternalId[M]] extends RepoTable[M] {
    def externalId = column[ExternalId[M]]("external_id", O.NotNull)
  }

  trait NamedColumns { self: RepoTable[_] =>
    lazy val _columnStrings = {
      create_*.map(_.name).take(30).map(db.entityName)
    }
    def columnStrings(tableName: String = tableName) = _columnStrings.map(c => tableName + "." + c).mkString(", ")
  }

  trait SeqNumberColumn[M <: ModelWithSeqNumber[M]] extends RepoTable[M] {
    implicit val seqMapper = MappedColumnType.base[SequenceNumber, Long](_.value, SequenceNumber.apply)

    def seq = column[SequenceNumber]("seq", O.NotNull)
  }
}


///////////////////////////////////////////////////////////
//                  more traits
///////////////////////////////////////////////////////////

trait RepoWithDelete[M <: Model[M]] { self: Repo[M] =>
  def delete(model: M)(implicit session:RWSession):Int

  // potentially more efficient variant but we currently depend on having the model available for our caches
  // def deleteCacheById(id: Id[M]): Int
  // def deleteById(id: Id[M])(implicit ev$0:ClassTag[M], session:RWSession):Int
}

trait DbRepoWithDelete[M <: Model[M]] extends RepoWithDelete[M] { self:DbRepo[M] =>

  def delete(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val target = for(t <- rows if t.id === model.id.get) yield t
    val count = target.delete
    deleteCache(model)
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:DELETE\tduration:${time}\ttype:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    count
  }
}


trait SeqNumberFunction[M <: ModelWithSeqNumber[M]]{ self: Repo[M] =>
  def getBySequenceNumber(lowerBound: SequenceNumber, fetchSize: Int = -1)(implicit session: RSession): Seq[M]
  def getBySequenceNumber(lowerBound: SequenceNumber, upperBound: SequenceNumber)(implicit session: RSession): Seq[M]
}

trait SeqNumberDbFunction[M <: ModelWithSeqNumber[M]] extends SeqNumberFunction[M] { self: DbRepo[M] =>

  protected def tableWithSeq(tag: Tag) = table(tag).asInstanceOf[SeqNumberColumn[M]]
  protected def rowsWithSeq = TableQuery(tableWithSeq)
  implicit val seqMapper = MappedColumnType.base[SequenceNumber, Long](_.value, SequenceNumber.apply)

  def getBySequenceNumber(lowerBound: SequenceNumber, fetchSize: Int = -1)(implicit session: RSession): Seq[M] = {
    val q = (for(t <- rowsWithSeq if t.seq > lowerBound) yield t).sortBy(_.seq)
    if (fetchSize > 0) q.take(fetchSize).list else q.list
  }

  def getBySequenceNumber(lowerBound: SequenceNumber, upperBound: SequenceNumber)(implicit session: RSession): Seq[M] = {
    if (lowerBound > upperBound) throw new IllegalArgumentException(s"expecting upperBound > lowerBound, received: lowerBound = ${lowerBound}, upperBound = ${upperBound}")
    else if (lowerBound == upperBound) Seq()
    else (for(t <- rowsWithSeq if t.seq > lowerBound && t.seq <= upperBound) yield t).sortBy(_.seq).list
  }
}


trait ExternalIdColumnFunction[M <: ModelWithExternalId[M]] { self: Repo[M] =>
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait ExternalIdColumnDbFunction[M <: ModelWithExternalId[M]] extends ExternalIdColumnFunction[M] { self: DbRepo[M] =>

  protected def tableWithExternalIdColumn(tag: Tag) = table(tag).asInstanceOf[ExternalIdColumn[M]]
  protected def rowsWithExternalIdColumn = TableQuery(tableWithExternalIdColumn)
  implicit val externalIdMapper = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])

  def get(id: ExternalId[M])(implicit session: RSession): M = getOpt(id).get

  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M] = {
    val startTime = System.currentTimeMillis()
    val model = (for(f <- rowsWithExternalIdColumn if f.externalId === id) yield f).firstOption
    val time = System.currentTimeMillis - startTime
    dbLog.info(s"t:${clock.now}\ttype:GET-EXT\tduration:${time}\tmodel:${model.getClass.getSimpleName()}\tmodel:${model.toString.abbreviate(200).trimAndRemoveLineBreaks}")
    model
  }
}

