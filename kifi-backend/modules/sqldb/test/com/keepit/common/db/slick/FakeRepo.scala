package com.keepit.common.db.slick

import com.keepit.common.db._
import scala.collection.concurrent.TrieMap
import DBSession._

class FakeRepo[M <: Model[M]] extends Repo[M] {
  val db = new TrieMap[Id[M], M]()
  def get(id: Id[M])(implicit session: RSession): M = db(id)
  def all()(implicit session: RSession): Seq[M] = db.values.toSeq
  def save(model: M)(implicit session: RWSession): M = {db(model.id.get) = model; model}
  def count(implicit session: RSession): Int = db.size
  def page(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[M] = db.values.drop(page * size).take(size).toSeq
}

