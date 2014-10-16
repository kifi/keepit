package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, ScoreContext }
import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.tracker.ResultClickBoosts
import scala.collection.mutable.ListBuffer

class ScoreDetailCollector(targetId: Long, clickBoostsProvider: Option[() => ResultClickBoosts], sharingBoost: Option[Float]) extends ResultCollector[ScoreContext] {

  private[this] var clickBoostValue: Float = -1f
  private[this] var sharingBoostValue: Float = -1f

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

    clickBoostsProvider.foreach { f =>
      val clickBoosts = f()
      clickBoostValue = clickBoosts(targetId)
    }

    sharingBoost.map { sharingBoost =>
      sharingBoostValue = (1.0f + sharingBoost - sharingBoost / ctx.degree.toFloat)
    }

    details("aggregate") += ScoreDetail(ctx)
  }

  def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {
    require(primaryId == targetId, "id mismatch")

    details(Visibility.name(visibility)) += ScoreDetail(primaryId, secondaryId, visibility, scoreArray)
  }

  def getDetails(): Map[String, Seq[ScoreDetail]] = details.mapValues(_.toSeq)
  def getBoostValues(): (Float, Float) = (clickBoostValue, sharingBoostValue)
}
