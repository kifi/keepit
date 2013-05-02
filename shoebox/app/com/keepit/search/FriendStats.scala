package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.IdMapper

object FriendStats{
  def apply(ids: Set[Id[User]]) = {
    new FriendStats(new ArrayIdMapper(ids.map(_.id).toArray))
  }
}

class FriendStats(mapper: IdMapper) {
  val scores = new Array[Float](mapper.maxDoc)

  def add(ids: Set[Id[User]], score: Float) {
    ids.foreach{ id =>
      val i = mapper.getDocId(id.id)
      if (i >= 0) scores(i) += score
    }
  }

  def score(id: Id[User]): Float = {
    val i = mapper.getDocId(id.id)
    if (i >= 0) scores(i) else 0.0f
  }
}
