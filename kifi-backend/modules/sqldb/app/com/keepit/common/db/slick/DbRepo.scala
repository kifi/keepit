package com.keepit.common.db.slick

import com.keepit.common.strings._
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import DBSession._
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.{ Statement, SQLException }
import play.api.Logger
import scala.slick.ast.{ TypedType, ColumnOption }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.logging.Logging
import scala.slick.lifted.{ AbstractTable, BaseTag }
import scala.slick.jdbc.StaticQuery

sealed trait RepoModification[M <: Model[M]] {
  val model: M
}
case class RepoEntryUpdated[M <: Model[M]](model: M) extends RepoModification[M]
case class RepoEntryAdded[M <: Model[M]](model: M) extends RepoModification[M]
case class RepoEntryRemoved[M <: Model[M]](model: M) extends RepoModification[M]

object RepoModification {
  type Listener[M <: Model[M]] = Function1[RepoModification[M], Unit]
}

trait Repo[M <: Model[M]] {
  def get(id: Id[M])(implicit session: RSession): M
  def getNoCache(id: Id[M])(implicit session: RSession): M
  def aTonOfRecords()(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
  def page(page: Int, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M]
  def pageAscendingIds(page: Int, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[Id[M]]
  def pageAscending(page: Int, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M]
  def invalidateCache(model: M)(implicit session: RSession): Unit
  def deleteCache(model: M)(implicit session: RSession): Unit
}

trait DbRepo[M <: Model[M]] extends Repo[M] with FortyTwoGenericTypeMappers with Logging {
  val db: DataBaseComponent
  val clock: Clock
  val profile = db.Driver.profile

  import db.Driver.simple._

  lazy val dbLog = Logger("com.keepit.db")

  protected val changeListener: Option[RepoModification.Listener[M]] = None

  type RepoImpl <: RepoTable[M]
  type RepoQuery = scala.slick.lifted.Query[RepoImpl, M, Seq]

  def table(tag: Tag): RepoImpl
  lazy val _taggedTable = table(new BaseTag { base =>
    def taggedAs(path: List[scala.slick.ast.Symbol]): AbstractTable[_] = base.taggedAs(path)
  })
  lazy val rows: TableQuery[RepoImpl] = {
    val t = TableQuery(table)
    db.initTable(_taggedTable.tableName, t.ddl)
    t
  }

  def initTable() = {
    rows // force `rows` lazy evaluationRe
  }

  def save(model: M)(implicit session: RWSession): M = try {
    val toUpdate = model.withUpdateTime(clock.now)
    val result = if (model.id.isDefined) update(toUpdate) else toUpdate.withId(insert(toUpdate))
    model match {
      case m: ModelWithState[M] if m.state == State[M]("inactive") => deleteCache(result)
      case _ => invalidateCache(result)
    }
    if (changeListener.isDefined) session.onTransactionSuccess {
      if (model.id.isDefined) changeListener.get(RepoEntryAdded(result))
      else changeListener.get(RepoEntryUpdated(result))
    }
    result
  } catch {
    case m: MySQLIntegrityConstraintViolationException =>
      session.directCacheAccess {
        deleteCache(model)
      }
      throw new MySQLIntegrityConstraintViolationException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}").initCause(m)
    case t: SQLException => throw new SQLException(s"error persisting ${model.toString.abbreviate(200).trimAndRemoveLineBreaks}", t)
  }

  def count(implicit session: RSession): Int = Query(rows.length).first

  protected val getCompiled = Compiled { id: Column[Id[M]] =>
    for (f <- rows if f.id === id) yield f
  }
  def get(id: Id[M])(implicit session: RSession): M = {
    getCompiled(id).firstOption.getOrElse(throw new IllegalArgumentException(s"can't find $id in ${_taggedTable.tableName}"))
  }

  final def getNoCache(id: Id[M])(implicit session: RSession): M = {
    getCompiled(id).firstOption.getOrElse(throw new IllegalArgumentException(s"can't find $id in ${_taggedTable.tableName}"))
  }

  def aTonOfRecords()(implicit session: RSession): Seq[M] = pageAscending(0, 2240, Set())

  def page(page: Int, size: Int, excludeStates: Set[State[M]])(implicit session: RSession): Seq[M] = {
    // todo(Andrew): When Slick 2.2 is released, convert to Compiled query (upgrade necessary for .take & .drop)
    val q = for {
      t <- rows if !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def pageAscendingIds(page: Int, size: Int, excludeStates: Set[State[M]])(implicit session: RSession): Seq[Id[M]] = {
    val q = for {
      t <- rows if !t.state.inSet(excludeStates)
    } yield t.id
    q.sortBy(_ asc).drop(page * size).take(size).list
  }

  def pageAscending(page: Int, size: Int, excludeStates: Set[State[M]])(implicit session: RSession): Seq[M] = {
    val q = for {
      t <- rows if !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id asc).drop(page * size).take(size).list
  }

  private def insert(model: M)(implicit session: RWSession): Id[M] = {
    val startTime = System.currentTimeMillis()
    val inserted = (rows returning rows.map(_.id)) += model
    inserted
  }

  private def update(model: M)(implicit session: RWSession) = {
    val startTime = System.currentTimeMillis()
    val target = getCompiled(model.id.get)
    val count = target.update(model)
    if (count != 1) {
      session.directCacheAccess {
        deleteCache(model)
      }
      throw new IllegalStateException(s"Updating $count models of [${model.toString.abbreviate(200).trimAndRemoveLineBreaks}] instead of exactly one. Maybe there is a cache issue. The actual model (from cache) is no longer in db.")
    }
    model
  }

  abstract class RepoTable[M <: Model[M]](val db: DataBaseComponent, tag: Tag, name: String) extends Table[M](tag: Tag, db.entityName(name)) with FortyTwoGenericTypeMappers with Logging {
    //standardizing the following columns for all entities
    def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)
    def createdAt = column[DateTime]("created_at", O.NotNull)
    def updatedAt = column[DateTime]("updated_at", O.NotNull)
    //state may not exist in all entities, if it does then its column name is standardized as well.
    def state = column[State[M]]("state", O.NotNull)

    //def autoInc = this returning Query(id)

    //H2 likes its column names in upper case where mysql does not mind.
    //the db component should figure it out
    override def column[C](name: String, options: ColumnOption[C]*)(implicit tm: TypedType[C]) =
      super.column(db.entityName(name), options: _*)
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
    def seq = column[SequenceNumber[M]]("seq", O.NotNull)
  }
}

///////////////////////////////////////////////////////////
//                  more traits
///////////////////////////////////////////////////////////

trait RepoWithDelete[M <: Model[M]] { self: Repo[M] =>
  def delete(model: M)(implicit session: RWSession): Int

  // potentially more efficient variant but we currently depend on having the model available for our caches
  // def deleteCacheById(id: Id[M]): Int
  // def deleteById(id: Id[M])(implicit ev$0:ClassTag[M], session:RWSession):Int
}

trait DbRepoWithDelete[M <: Model[M]] extends RepoWithDelete[M] { self: DbRepo[M] =>
  import db.Driver.simple._

  def delete(model: M)(implicit session: RWSession) = {
    model.id match {
      case None =>
        throw new IllegalArgumentException(s"[delete] attempt to delete record without id. model=$model")
      case Some(id) =>
        val target = for (t <- rows if t.id === id) yield t
        val count = target.delete
        deleteCache(model)
        if (changeListener.isDefined) session.onTransactionSuccess {
          changeListener.get(RepoEntryRemoved(model))
        }
        count
    }
  }
}

trait SeqNumberFunction[M <: ModelWithSeqNumber[M]] { self: Repo[M] =>
  def getBySequenceNumber(lowerBound: SequenceNumber[M], fetchSize: Int = -1)(implicit session: RSession): Seq[M]
  def getBySequenceNumber(lowerBound: SequenceNumber[M], upperBound: SequenceNumber[M])(implicit session: RSession): Seq[M]
  def assignSequenceNumbers(limit: Int)(implicit session: RWSession): Int
  def minDeferredSequenceNumber()(implicit session: RSession): Option[Long]
  def maxSequenceNumber()(implicit session: RSession): Option[SequenceNumber[M]]
}

trait SeqNumberDbFunction[M <: ModelWithSeqNumber[M]] extends SeqNumberFunction[M] { self: DbRepo[M] =>
  import db.Driver.simple._

  protected lazy val sequence = db.getSequence[M](_taggedTable.tableName + "_sequence")
  protected def tableWithSeq(tag: Tag) = table(tag).asInstanceOf[SeqNumberColumn[M]]
  protected def rowsWithSeq = TableQuery(tableWithSeq)

  def getBySequenceNumber(lowerBound: SequenceNumber[M], fetchSize: Int)(implicit session: RSession): Seq[M] = {
    // todo(Andrew): When Slick 2.1 is released, convert to Compiled query (upgrade necessary for .take)
    val q = (for (t <- rowsWithSeq if t.seq > lowerBound) yield t).sortBy(_.seq)
    val realSize = if (fetchSize >= 0) fetchSize else 400 // because we have tests that send -1, which blows my mind.
    q.take(realSize).list
  }

  def getBySequenceNumber(lowerBound: SequenceNumber[M], upperBound: SequenceNumber[M])(implicit session: RSession): Seq[M] = {
    if (lowerBound > upperBound) throw new IllegalArgumentException(s"expecting upperBound > lowerBound, received: lowerBound = ${lowerBound}, upperBound = ${upperBound}")
    else if (lowerBound == upperBound) Seq()
    else (for (t <- rowsWithSeq if t.seq > lowerBound && t.seq <= upperBound) yield t).sortBy(_.seq).list
  }

  protected def deferredSeqNum(): SequenceNumber[M] = SequenceNumber[M](clock.now.getMillis() - Long.MaxValue)

  def assignSequenceNumbers(limit: Int)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, _taggedTable.tableName, limit)
  }

  protected def assignSequenceNumbers(sequence: DbSequence[M], tableName: String, limit: Int)(implicit session: RWSession): Int = {
    // todo(Andrew): When Slick 2.1 is released, convert to Compiled query (upgrade necessary for .take)
    val zero = SequenceNumber.ZERO[M]
    val ids = (for (t <- rowsWithSeq if t.seq < zero) yield t).sortBy(_.seq).map(_.id).take(limit).list
    val numIds = ids.size

    val totalUpdates = if (numIds > 0) {
      val iterator = sequence.reserve(numIds).iterator
      val stmt = session.getPreparedStatement(s"update $tableName set seq = ? where id = ?")
      ids.foreach { id =>
        if (!iterator.hasNext) throw new IllegalStateException("ran out of reserved sequence number")
        stmt.setLong(1, iterator.next.value)
        stmt.setLong(2, id.id)
        stmt.addBatch()
      }
      if (iterator.hasNext) throw new IllegalStateException("not all reserved sequence numbers were used")

      stmt.executeBatch().map {
        case numUpdates if (numUpdates == Statement.EXECUTE_FAILED) => throw new IllegalStateException("one of sequence number updates failed")
        case numUpdates if (numUpdates == Statement.SUCCESS_NO_INFO) => 1
        case numUpdates => numUpdates
      }.sum
    } else {
      0
    }

    if (totalUpdates != numIds) throw new IllegalStateException(s"total update counts did not match: total=$totalUpdates numIds=$numIds]")

    numIds
  }

  def minDeferredSequenceNumber()(implicit session: RSession): Option[Long] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select min(seq) from #${_taggedTable.tableName} where seq < 0""".as[Option[Long]].first
  }

  def maxSequenceNumber()(implicit session: RSession): Option[SequenceNumber[M]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""select max(seq) from #${_taggedTable.tableName} where seq >= 0""".as[Option[SequenceNumber[M]]].first
  }
}

trait ExternalIdColumnFunction[M <: ModelWithExternalId[M]] { self: Repo[M] =>
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
  def convertExternalIds(ids: Set[ExternalId[M]])(implicit session: RSession): Map[ExternalId[M], Id[M]]
  def convertExternalId(id: ExternalId[M])(implicit session: RSession): Id[M]
}

trait ExternalIdColumnDbFunction[M <: ModelWithExternalId[M]] extends ExternalIdColumnFunction[M] { self: DbRepo[M] =>
  import db.Driver.simple._

  protected def tableWithExternalIdColumn(tag: Tag) = table(tag).asInstanceOf[ExternalIdColumn[M]]
  protected def rowsWithExternalIdColumn = TableQuery(tableWithExternalIdColumn)
  implicit val externalIdMapper = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])

  def get(id: ExternalId[M])(implicit session: RSession): M = getOpt(id).getOrElse(throw new Exception(s"Can't find entity for id $id"))

  protected val getByExtIdCompiled = Compiled { id: Column[ExternalId[M]] =>
    for (f <- rowsWithExternalIdColumn if f.externalId === id) yield f
  }

  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M] = getByExtIdCompiled(id).firstOption

  def convertExternalIds(ids: Set[ExternalId[M]])(implicit session: RSession): Map[ExternalId[M], Id[M]] = {
    rowsWithExternalIdColumn.filter(r => r.externalId.inSet(ids)).map(r => (r.externalId, r.id)).list.toMap
  }

  def convertExternalId(id: ExternalId[M])(implicit session: RSession): Id[M] = convertExternalIds(Set(id))(session)(id)
}

/**
 * * A fix for warnings when using Slick's SQL interpolation with no $vals in the string. This can be removed for Slick 2.2
 * * to use,
 * *   import myutils.SQI.interpolation
 * * rather then
 * *   import scala.slick.jdbc.StaticQueryFixed.interpolation
 */

class SQLInterpolation_WarningsFixed(val s: StringContext) extends AnyVal {
  import scala.slick.jdbc._
  def sql[P](param: P)(implicit pconv: SetParameter[P]) =
    new SQLInterpolationResult[P](s.parts, param, pconv)
  def sqlu[P](param: P)(implicit pconv: SetParameter[P]) = sql(param).asUpdate
  // The warning occurs because when there are no $vars in the string interpolation, param is Unit
  // and Scala now warns if we're not explicit about it. The methods below satisfy that need:
  def sql[P]()(implicit pconv: SetParameter[Unit]) =
    new SQLInterpolationResult[Unit](s.parts, (), pconv)
  def sqlu[P]()(implicit pconv: SetParameter[Unit]) = sql(()).asUpdate
}

object StaticQueryFixed {
  import scala.language.implicitConversions
  @inline implicit def interpolation(s: StringContext): SQLInterpolation_WarningsFixed = new SQLInterpolation_WarningsFixed(s)
}

