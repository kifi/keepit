package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.engine.result.ResultCollector
import scala.collection.mutable.ListBuffer

class ScoreDetailCollector(targetId: Long) extends ResultCollector[ScoreContext] {

  private[this] val details: Map[String, ListBuffer[ScoreDetail]] = Map(
    "aggregate" -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OWNER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.MEMBER) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.NETWORK) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.OTHERS) -> new ListBuffer[ScoreDetail](),
    Visibility.name(Visibility.RESTRICTED) -> new ListBuffer[ScoreDetail]()
  )

  def collect(ctx: ScoreContext): Unit = {
    require(ctx.id == targetId, "id mismatch")

    details("aggregate") += ScoreDetail(ctx)
  }

  def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    require(primaryId == targetId, "id mismatch")

    details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray)
  }

  def get(): Map[String, Seq[ScoreDetail]] = details.mapValues(_.toSeq)
}
