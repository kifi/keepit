package com.keepit.common.db.slick

import com.keepit.common.db.{Id, Model}
import com.keepit.inject._

import org.scalaquery.ql._
import play.api.Play.current

import org.scalaquery.ql.extended.ExtendedTable

trait Repo[M] {
  def save(model: M): M
  def count: Int
}

trait DbRepo[M <: Model[M]] extends Repo[M] {
  implicit val db = inject[DataBaseComponent]
  import db.Driver.Implicit._ // here's the driver, abstracted away

  protected def table: ExtendedTable[M]

  def save(model: M): M = {
    val sequenceID = insert(model)
    model.withId(sequenceID)
  }

  private def insert(model: M) = db.readWrite {implicit session =>
    // here you would do the insert/save logic and update the 'updatedAt' field
    assert(1 == table.insert(model))
    Id[M](Query(db.sequenceID).first)
  }

  def count = db.readWrite {implicit s => Query(table.count).first }
}
