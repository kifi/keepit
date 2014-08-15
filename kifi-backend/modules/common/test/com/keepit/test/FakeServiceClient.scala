package com.keepit.test

import java.util.concurrent.ConcurrentHashMap

import com.keepit.common.db.{ Model, Id }

trait FakeServiceClient {

  def makeRepoBase[T <: Model[T]]: FakeRepoBase[T] = new FakeRepoBase[T] {
    val idCounter = new FakeIdCounter[T]
    val data = new ConcurrentHashMap[Id[T], T]()
  }

  def save[T <: Model[T]](models: T*)(implicit repo: FakeRepoLike[T]): Seq[T] = models map { repo.save }

}
