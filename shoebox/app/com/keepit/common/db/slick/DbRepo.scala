package com.keepit.common.db.slick

import com.keepit.common.db._
import com.keepit.inject._
import org.joda.time.DateTime
import org.scalaquery.ql._
import org.scalaquery.ql.ColumnOps._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.ql.extended.ExtendedTable
import org.scalaquery.util.{Node, UnaryNode, BinaryNode}
import DBSession._
import play.api.Play.current
import org.scalaquery.ql.extended.ExtendedProfile
import org.scalaquery.ql.extended.ExtendedColumnOptions
import org.scalaquery.ql.extended.ExtendedImplicitConversions
import org.scalaquery.ql.Ordering.Desc


trait Repo[M <: Model[M]] {
  import DBSession._
  def get(id: Id[M])(implicit session: RSession): M
  def all()(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
  def page(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[M]
}

trait RepoWithExternalId[M <: ModelWithExternalId[M]] { self: Repo[M] =>
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait DbRepo[M <: Model[M]] extends Repo[M] {
  import FortyTwoTypeMappers._
  val db: DataBaseComponent
  import db.Driver.Implicit._ // here's the driver, abstracted away

  def invalidateCache(model: M): M = model

  implicit val idMapper = new BaseTypeMapper[Id[M]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[M]
  }

  implicit val stateTypeMapper = new BaseTypeMapper[State[M]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[M]
  }


  protected def table: RepoTable[M]

  def descTable(): String = db.handle.withSession {
    table.ddl.createStatements mkString "\n"
  }

  def save(model: M)(implicit session: RWSession): M = {
    val toUpdate = model.withUpdateTime(inject[DateTime])
    val result = model.id match {
      case Some(id) => update(toUpdate)
      case None => toUpdate.withId(insert(toUpdate))
    }
    invalidateCache(result)
  }

  def count(implicit session: RSession): Int = Query(table.count).first

  def get(id: Id[M])(implicit session: RSession): M = (for(f <- table if f.id is id) yield f).first

  def all()(implicit session: RSession): Seq[M] = table.map(t => t).list

  def page(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[M] =  {
    val q = for {
      t <- table
      _ <- Query.orderBy(t.id desc)
    } yield t
    q.drop(page * size).take(size).list
  }

  private def insert(model: M)(implicit session: RWSession) = {
    assert(1 == table.insert(model))
    Id[M](Query(db.sequenceID).first)
  }

  private def update(model: M)(implicit session: RWSession) = {
    implicit val mapper = new BaseTypeMapper[Id[M]] {
      def apply(profile: BasicProfile) = new IdMapperDelegate[M]
    }
    assert(1 == table.where(r => Is(r.id, model.id.get)).update(model))
    model
  }

}

trait ExternalIdColumnFunction[M <: ModelWithExternalId[M]] {
  def get(id: ExternalId[M])(implicit session: RSession): M
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M]
}

trait ExternalIdColumnDbFunction[M <: ModelWithExternalId[M]] extends RepoWithExternalId[M] { self: DbRepo[M] =>
  import db.Driver.Implicit._
  protected def externalIdColumn: ExternalIdColumn[M] = table.asInstanceOf[ExternalIdColumn[M]]

  implicit val externalIdMapper = new BaseTypeMapper[ExternalId[M]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[M]
  }

  def get(id: ExternalId[M])(implicit session: RSession): M = getOpt(id).get
  def getOpt(id: ExternalId[M])(implicit session: RSession): Option[M] = (for(f <- externalIdColumn if Is(f.externalId, id)) yield f).firstOption
}

/**
 * The toUpperCase is per an H2 "bug?"
 * http://stackoverflow.com/a/8722814/81698
 */
abstract class RepoTable[M <: Model[M]](db: DataBaseComponent, name: String) extends ExtendedTable[M](db.entityName(name)) {
  import FortyTwoTypeMappers._

  implicit val idMapper = new BaseTypeMapper[Id[M]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[M]
  }

  implicit def stateMapper = new BaseTypeMapper[State[M]] {
    def apply(profile: BasicProfile) = new StateMapperDelegate[M]
  }

  def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)

  def createdAt = column[DateTime]("created_at", O.NotNull)
  def updatedAt = column[DateTime]("updated_at", O.NotNull)

  def state = column[State[M]]("state", O.NotNull)

  override def column[C : TypeMapper](name: String, options: ColumnOption[C, ProfileType]*) =
    super.column(db.entityName(name), options:_*)
}

trait ExternalIdColumn[M <: ModelWithExternalId[M]] extends RepoTable[M] {
  implicit val externalIdMapper = new BaseTypeMapper[ExternalId[M]] {
    def apply(profile: BasicProfile) = new ExternalIdMapperDelegate[M]
  }

  def externalId = column[ExternalId[M]]("external_id", O.NotNull)
}



