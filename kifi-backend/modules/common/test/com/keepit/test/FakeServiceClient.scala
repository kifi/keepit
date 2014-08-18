package com.keepit.test

import java.util.concurrent.ConcurrentHashMap

import com.keepit.common.db.Id

trait FakeServiceClient {

  def makeRepoBase[K, V]: FakeRepoBase[K, V] = new FakeRepoBase[K, V] {
    val data = new ConcurrentHashMap[K, V]()
  }

  def put[K, V](key: K, value: V)(implicit repo: FakeRepoLike[K, V]): Unit = repo.put(key, value)

  def makeRepoWithId[T <: { def id: Option[Id[T]]; def withId(id: Id[T]): T }]: FakeRepoWithId[T] = new FakeRepoWithId[T]

  // quack!
  def save[T <: { def id: Option[Id[T]] }](datums: T*)(implicit repo: FakeRepoWithId[T]): Seq[T] = datums map { repo.save }
}
