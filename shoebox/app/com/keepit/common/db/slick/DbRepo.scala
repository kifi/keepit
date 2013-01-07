package com.keepit.common.db.slick

import com.keepit.common.db.{Id, Model}
import com.keepit.inject._

import org.joda.time.DateTime

import org.scalaquery.ql._
import org.scalaquery.ql.ColumnOps._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.basic.BasicProfile
import org.scalaquery.ql.extended.ExtendedTable
import org.scalaquery.util.{Node, UnaryNode, BinaryNode}

import play.api.Play.current


trait Repo[M <: Model[M]] {
  import DBSession._
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
}

trait DbRepo[M <: Model[M]] extends Repo[M] {
  implicit val db = inject[DataBaseComponent]
  import db.Driver.Implicit._ // here's the driver, abstracted away
  import DBSession._

  protected def table: RepoTable[M]

  def save(model: M)(implicit session: RWSession): M = {
    val toUpdate = model.withUpdateTime(inject[DateTime])
    model.id match {
      case Some(id) => update(toUpdate)
      case None => toUpdate.withId(insert(toUpdate))
    }
  }

  def count(implicit session: RSession): Int = Query(table.count).first

  private def insert(model: M)(implicit session: RWSession) = {
    assert(1 == table.insert(model))
    Id[M](Query(db.sequenceID).first)
  }

  private def update(model: M)(implicit session: RWSession) = {
    assert(1 == (for(r <- table if Is(r.id, Node(model.id))) yield(r)).update(model))
    model
  }

}

/**
 * The toUpperCase is per an H2 "bug?"
 * http://stackoverflow.com/a/8722814/81698
 */
abstract class RepoTable[M <: Model[M]](name: String) extends ExtendedTable[M](name.toUpperCase()) {
  import FortyTwoTypeMappers._

  def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)(new BaseTypeMapper[Id[M]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[M]
  })

  def createdAt = column[DateTime]("CREATED_AT", O.NotNull)
  def updatedAt = column[DateTime]("UPDATED_AT", O.NotNull)

  def idCreateUpdateBase = id.? ~ createdAt ~ updatedAt

  override def column[C : TypeMapper](n: String, options: ColumnOption[C, ProfileType]*) = super.column(n.toUpperCase(), options:_*)
}

