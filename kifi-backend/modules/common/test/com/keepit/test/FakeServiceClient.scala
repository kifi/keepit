package com.keepit.test

import collection.mutable.{ Map => MutableMap }

import com.keepit.common.db.{ Model, Id }

trait FakeServiceClient {

  def makeRepoBase[T <: Model[T]]: FakeRepoBase[T] = new FakeRepoBase[T] {
    val idCounter = new FakeIdCounter[T]
    val data = MutableMap[Id[T], T]()
  }

  def save[T <: Model[T]](models: T*)(implicit repo: FakeRepoLike[T]): Seq[T] = models map { repo.save }

}
