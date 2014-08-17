package com.keepit.test

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
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

trait Datum[T] {
  def id(datum: T): Option[Id[T]]
  def withId(datum: T, id: Id[T]): T
}

object Datum {
  implicit val keepDiscoveryDatum = new Datum[KeepDiscovery] {
    override def id(datum: KeepDiscovery): Option[Id[KeepDiscovery]] = datum.id
    override def withId(datum: KeepDiscovery, id: Id[KeepDiscovery]): KeepDiscovery = datum.withId(id)
  }
  implicit val rekeepDatum = new Datum[ReKeep] {
    override def id(datum: ReKeep): Option[Id[ReKeep]] = datum.id
    override def withId(datum: ReKeep, id: Id[ReKeep]): ReKeep = datum.withId(id)
  }
}
