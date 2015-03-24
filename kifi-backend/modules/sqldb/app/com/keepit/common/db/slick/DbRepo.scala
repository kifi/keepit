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
  def all()(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M]
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
    val startTime = System.currentTimeMillis()
    val model = getCompiled(id).firstOption.getOrElse(throw new IllegalArgumentException(s"can't find $id in ${_taggedTable.tableName}"))
    model
  }

  def all()(implicit session: RSession): Seq[M] = rows.list

  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[M]] = Set.empty[State[M]])(implicit session: RSession): Seq[M] = {
    // todo(Andrew): When Slick 2.1 is released, convert to Compiled query (upgrade necessary for .take & .drop)
    val q = for {
      t <- rows if !t.state.inSet(excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
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
      deleteCache(model)
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
}

trait SeqNumberDbFunction[M <: ModelWithSeqNumber[M]] extends SeqNumberFunction[M] { self: DbRepo[M] =>
  import db.Driver.simple._

  protected lazy val sequence = db.getSequence[M](_taggedTable.tableName + "_sequence")
  protected def tableWithSeq(tag: Tag) = table(tag).asInstanceOf[SeqNumberColumn[M]]
  protected def rowsWithSeq = TableQuery(tableWithSeq)

  def getBySequenceNumber(lowerBound: SequenceNumber[M], fetchSize: Int = -1)(implicit session: RSession): Seq[M] = {
    // todo(Andrew): When Slick 2.1 is released, convert to Compiled query (upgrade necessary for .take)
    val q = (for (t <- rowsWithSeq if t.seq > lowerBound) yield t).sortBy(_.seq)
    if (fetchSize > 0) q.take(fetchSize).list else q.list
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
    import StaticQuery.interpolation
    sql"""select min(seq) from #${_taggedTable.tableName} where seq < 0""".as[Option[Long]].first
  }
}

trait ExternalIdColumnFunction[M <: ModelWithExternalId[M]] { self: Repo[M] =>
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait ExternalIdColumnDbFunction[M <: ModelWithExternalId[M]] extends ExternalIdColumnFunction[M] { self: DbRepo[M] =>
  import db.Driver.simple._

  protected def tableWithExternalIdColumn(tag: Tag) = table(tag).asInstanceOf[ExternalIdColumn[M]]
  protected def rowsWithExternalIdColumn = TableQuery(tableWithExternalIdColumn)
  implicit val externalIdMapper = MappedColumnType.base[ExternalId[M], String](_.id, ExternalId[M])

  def get(id: ExternalId[M])(implicit session: RSession): M = getOpt(id).get

  protected val getByExtIdCompiled = Compiled { id: Column[ExternalId[M]] =>
    for (f <- rowsWithExternalIdColumn if f.externalId === id) yield f
  }

  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M] = getByExtIdCompiled(id).firstOption
}

