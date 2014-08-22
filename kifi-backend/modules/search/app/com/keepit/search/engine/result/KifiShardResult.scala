package com.keepit.search.engine.result

import com.keepit.common.logging.Logging
import scala.math.BigDecimal.double2bigDecimal
import scala.math.BigDecimal.int2bigDecimal
import scala.math.BigDecimal.long2bigDecimal
import play.api.libs.json._

class KifiShardResult(val json: JsObject) extends AnyVal {
  def hits: Seq[KifiShardHit] = (json \ "hits").as[JsArray] match {
    case JsArray(hits) => hits.map { json => new KifiShardHit(json.as[JsObject]) }
    case _ => Seq.empty
  }
  def myTotal: Int = (json \ "myTotal").as[Int]
  def friendsTotal: Int = (json \ "friendsTotal").as[Int]
  def show: Boolean = (json \ "show").as[Boolean]
  def cutPoint: Int = (json \ "cutPoint").as[Int]
}

object KifiShardResult extends Logging {
  def apply(hits: Seq[KifiShardHit], myTotal: Int, friendsTotal: Int, show: Boolean, cutPoint: Int = 0): KifiShardResult = {
    try {
      new KifiShardResult(JsObject(List(
        "hits" -> JsArray(hits.map(_.json)),
        "myTotal" -> JsNumber(myTotal),
        "friendsTotal" -> JsNumber(friendsTotal),
        "show" -> JsBoolean(show),
        "cutPoint" -> JsNumber(cutPoint)
      )))
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiShardResult [hits=$hits][myTotal=$myTotal][friendsTotal=$friendsTotal][show=$show][cutPoint=$cutPoint]", e)
        throw e
    }
  }
  lazy val empty: KifiShardResult = {
    new KifiShardResult(JsObject(List(
      "hits" -> JsArray(),
      "myTotal" -> JsNumber(0),
      "friendsTotal" -> JsNumber(0),
      "show" -> JsBoolean(false),
      "cutPoint" -> JsNumber(0)
    )))
  }
}

class KifiShardHit(val json: JsObject) extends AnyVal {
  def id: Long = (json \ "id").as[Long]
  def score: Float = (json \ "score").as[Float]
  def visibility: Int = (json \ "visibility").as[Int]
  def libraryId: Option[Long] = (json \ "libId").asOpt[Long]

  def set(key: String, value: JsValue): KifiShardHit = {
    new KifiShardHit((json - key) + (key -> value))
  }
}

object KifiShardHit extends Logging {
  def apply(id: Long, score: Float, visibility: Int, libraryId: Option[Long]): KifiShardHit = {
    apply(id, score, visibility, libraryId.getOrElse(-1L))
  }

  def apply(id: Long, score: Float, visibility: Int, libraryId: Long): KifiShardHit = {
    try {
      var json = JsObject(List(
        "id" -> JsNumber(id),
        "score" -> JsNumber(score.toDouble),
        "visibility" -> JsNumber(visibility)
      ))

      if (libraryId >= 0) { json = json + ("libId" -> JsNumber(libraryId)) }
      new KifiShardHit(json)
    } catch {
      case e: Throwable =>
        log.error(s"can't serialize KifiShardHit [id=$id][score=$score][visibility=$visibility]", e)
        throw e
    }
  }
}

