package com.keepit.test

import java.util.concurrent.atomic.AtomicLong
import collection.mutable.{ Map => MutableMap }

import com.keepit.common.db.{ Model, Id }

trait FakeServiceClient {

  class FakeIdCounter[T <: Model[T]] {
    private val counter = new AtomicLong(0)
    def nextId(): Id[T] = { Id[T](counter.incrementAndGet) }
  }

  class FakeRepo[T <: Model[T]] {
    val idCounter = new FakeIdCounter[T]()
    val data = MutableMap[Id[T], T]()
    def saveOne(model: T): T = {
      val id = model.id.getOrElse(idCounter.nextId())
      val updated = model.withId(id)
      data(id) = updated
      updated
    }
    def save(model: T*): Seq[T] = model map { saveOne(_) }
    def count: Int = data.keySet.size
    def filter(p: T => Boolean) = data.values.filter(p)
    def dump() = println(data)
  }

  def save[T <: Model[T]](models: T*)(implicit repo: FakeRepo[T]): Seq[T] = repo.save(models: _*)
}
