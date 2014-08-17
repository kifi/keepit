package com.keepit.test

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ Id }
import com.keepit.model.{ KeepDiscovery, ReKeep }

import collection.JavaConversions._

trait FakeRepoLike[K, V] {

  def get(key: K): V
  def put(key: K, value: V): V
  def all(): Seq[V]
  def count: Int
}

class FakeIdCounter[T] {
  private val counter = new AtomicLong(0)
  def nextId(): Id[T] = { Id[T](counter.incrementAndGet) }
}

trait FakeRepoBase[K, V] extends FakeRepoLike[K, V] {
  def data: ConcurrentHashMap[K, V]

  def put(key: K, value: V): V = {
    data.put(key, value)
  }
  def get(key: K) = data.get(key)
  def all() = data.values.toSeq // add shuffle if not random enough
  def count: Int = data.keySet.size
  def invalidateCache(model: K): Unit = {}
  def deleteCache(model: K): Unit = {}

  def filter(p: V => Boolean) = data.values.filter(p)
}

// referencing Model in common is forbidden (quacking is expensive -- revisit)
class FakeRepoWithId[T <: { def id: Option[Id[T]]; def withId(id: Id[T]): T }] extends FakeRepoBase[Id[T], T] {
  val idCounter = new FakeIdCounter[T]
  val data = new ConcurrentHashMap[Id[T], T]()

  def save(t: T): T = {
    val id = t.id getOrElse idCounter.nextId
    data.put(id, t)
  }
}