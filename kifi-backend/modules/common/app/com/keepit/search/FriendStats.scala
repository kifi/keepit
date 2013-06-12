package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.IdMapper

object FriendStats{
  def apply(ids: Set[Id[User]]) = {
    val mapper = new ArrayIdMapper(ids.map(_.id).toArray)
    val scores = new Array[Float](mapper.maxDoc)
    new FriendStats(mapper, scores)
  }
}

class FriendStats(mapper: IdMapper, scores: Array[Float]) {

  def add(friendIds: Set[Long], score: Float) {
    friendIds.foreach{ id =>
      val i = mapper.getDocId(id) // index into the friend id array
      if (i >= 0) scores(i) += score
    }
  }

  def score(id: Id[User]): Float = score(id.id)

  def score(id: Long): Float = {
    val i = mapper.getDocId(id)
    if (i >= 0) scores(i) else 0.0f
  }

  def normalize(): FriendStats = {
    val normalizedScores = scores.clone
    var i = 0
    var maxScore = 0.0f
    while (i < scores.length) {
      if (scores(i) > maxScore) maxScore = scores(i)
      i += 1
    }
    i = 0
    while (i < scores.length) {
      scores(i) = scores(i) / maxScore
      i += 1
    }

    new FriendStats(mapper, normalizedScores)
  }
}
