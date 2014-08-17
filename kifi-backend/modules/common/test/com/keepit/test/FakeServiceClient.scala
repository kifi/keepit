package com.keepit.test

import java.util.concurrent.ConcurrentHashMap

import com.keepit.common.db.{ Model, Id }

trait FakeServiceClient {

  def makeRepoBase[K, V]: FakeRepoBase[K, V] = new FakeRepoBase[K, V] {
    val data = new ConcurrentHashMap[K, V]()
  }

  def save[K, V](key: K, value: V)(implicit repo: FakeRepoLike[K, V]): Unit = repo.put(key, value)

}

trait ServiceHelper {
  def save[T: Datum](data: T)(repo: FakeRepoBase[Id[T], T]): T = {
    val datumHelper = implicitly[Datum[T]]
    repo.put(datumHelper.id(data).get, data)
  }
}