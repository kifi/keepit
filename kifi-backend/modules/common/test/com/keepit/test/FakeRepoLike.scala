package com.keepit.test

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ State, Id, Model }
import collection.mutable.{ Map => MutableMap }

trait FakeRepoLike[T <: Model[T]] {

  def save(model: T): T
  def get(id: Id[T]): T
  def all(): Seq[T]
  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[T]] = Set.empty[State[T]]): Seq[T]
  def count: Int
  def invalidateCache(model: T): Unit
  def deleteCache(model: T): Unit

}

class FakeIdCounter[T <: Model[T]] {
  private val counter = new AtomicLong(0)
  def nextId(): Id[T] = { Id[T](counter.incrementAndGet) }
}

trait FakeRepoBase[T <: Model[T]] extends FakeRepoLike[T] {
  def idCounter: FakeIdCounter[T]
  def data: MutableMap[Id[T], T]

  def save(model: T): T = {
    val id = model.id.getOrElse(idCounter.nextId())
    val updated = model.withId(id)
    data(id) = updated
    updated
  }
  def get(id: Id[T]) = data(id)
  def all() = data.values.toSeq
  def page(page: Int = 0, size: Int = 20, excludeStates: Set[State[T]]) = data.values.toSeq.sortBy(_.id.get).drop(page * size).take(size).toSeq
  def count: Int = data.keySet.size
  def invalidateCache(model: T): Unit = {}
  def deleteCache(model: T): Unit = {}

  def filter(p: T => Boolean) = data.values.filter(p)
}
