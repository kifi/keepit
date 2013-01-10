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
  def get(id: Id[M])(implicit session: RSession): M
  def all(implicit session: RSession): Seq[M]
  def save(model: M)(implicit session: RWSession): M
  def count(implicit session: RSession): Int
}

trait DbRepo[M <: Model[M]] extends Repo[M] {
  import FortyTwoTypeMappers._
  val db: DataBaseComponent
  import db.Driver.Implicit._ // here's the driver, abstracted away

  import DBSession._

  implicit val IdMapper = new BaseTypeMapper[Id[M]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[M]
  }

  protected def table: RepoTable[M]

  def descTable(): String = db.handle.withSession {
    table.ddl.createStatements mkString "\n"
  }

  def save(model: M)(implicit session: RWSession): M = {
    val toUpdate = model.withUpdateTime(inject[DateTime])
    model.id match {
      case Some(id) => update(toUpdate)
      case None => toUpdate.withId(insert(toUpdate))
    }
  }

  def count(implicit session: RSession): Int = Query(table.count).first

  def get(id: Id[M])(implicit session: RSession): M = (for(f <- table if f.id is id) yield f).first

  def all(implicit session: RSession): Seq[M] = (for(f <- table) yield f).list

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

/**
 * The toUpperCase is per an H2 "bug?"
 * http://stackoverflow.com/a/8722814/81698
 */
abstract class RepoTable[M <: Model[M]](name: String) extends ExtendedTable[M](name.toUpperCase()) {
  import FortyTwoTypeMappers._

  implicit val IdMapper = new BaseTypeMapper[Id[M]] {
    def apply(profile: BasicProfile) = new IdMapperDelegate[M]
  }

  def id = column[Id[M]]("ID", O.PrimaryKey, O.Nullable, O.AutoInc)

  def createdAt = column[DateTime]("CREATED_AT", O.NotNull)
  def updatedAt = column[DateTime]("UPDATED_AT", O.NotNull)

  def idCreateUpdateBase = id.? ~ createdAt ~ updatedAt

  override def column[C : TypeMapper](n: String, options: ColumnOption[C, ProfileType]*) = super.column(n.toUpperCase(), options:_*)
}

