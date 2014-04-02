package com.keepit.search.result

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.IdMapper
import play.api.libs.json._
import scala.math.BigDecimal.double2bigDecimal
import scala.math.BigDecimal.long2bigDecimal

object FriendStats extends Logging {
  def apply(idSet: Set[Long]) = {
    val ids = idSet.toArray
    val mapper = new ArrayIdMapper(ids)
    val scores = new Array[Float](mapper.maxDoc)
    new FriendStats(ids, scores, mapper)
  }

  def apply(ids: Array[Long], scores: Array[Float]) = {
    val mapper = new ArrayIdMapper(ids)
    new FriendStats(ids, scores, mapper)
  }

  val empty: FriendStats = FriendStats(Array[Long](), Array[Float]())

  implicit val format: Format[FriendStats] = new Format[FriendStats] {
    def writes(res: FriendStats): JsValue = {
      try {
        JsObject(List(
          "ids" -> JsArray(res.ids.map(JsNumber(_))),
          "scores" -> JsArray(res.scores.map(JsNumber(_)))
        ))
      } catch {
        case e: Throwable =>
          log.error(s"can't serialize $res")
          throw e
      }
    }

    def reads(json: JsValue): JsResult[FriendStats] = JsSuccess(
      try {
        FriendStats(
          (json \ "ids").as[Seq[Long]].toArray,
          (json \ "scores").as[Seq[Float]].toArray
        )
      } catch {
        case e: Throwable =>
          log.error(s"can't deserialize serialize $json")
          throw e
      }
    )
  }
}

class FriendStats(val ids: Array[Long], val scores: Array[Float], mapper: IdMapper) {

  def add(friendId: Long, score: Float): Unit = {
    val i = mapper.getDocId(friendId) // index into the friend id array
    if (i >= 0) scores(i) += score
  }

  def score(friendId: Id[User]): Float = score(friendId.id)

  def score(friendId: Long): Float = {
    val i = mapper.getDocId(friendId)
    if (i >= 0) scores(i) else 0.0f
  }

  def normalize(): FriendStats = {
    var i = 0
    var maxScore = 0.0f
    while (i < scores.length) {
      if (scores(i) > maxScore) maxScore = scores(i)
      i += 1
    }
    i = 0
    if (maxScore > 0.0f) {
      val normalizedScores = scores.clone
      while (i < scores.length) {
        scores(i) = scores(i) / maxScore
        i += 1
      }
      new FriendStats(ids, normalizedScores, mapper)
    } else {
      this
    }
  }

  override def toString(): String = {
    s"FriendStats(ids=(${ids.mkString(",")}), scores=(${scores.mkString(",")}))"
  }
}
