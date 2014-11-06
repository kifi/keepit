package com.keepit.search.engine.explain

import com.keepit.search.engine.{ Visibility, ScoreContext }

object ScoreDetail {
  def apply(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]) = {
    new ScoreDetail(primaryId, secondaryId, visibility, scoreArray.clone, None)
  }
  def apply(ctx: ScoreContext) = {
    new ScoreDetail(ctx.id, ctx.secondaryId, ctx.visibility, ctx.scoreMax.clone, Some(ctx.scoreSum.clone))
  }
}

class ScoreDetail(val primaryId: Long, val secondaryId: Long, val visibility: Int, val scoreMax: Array[Float], val scoreSum: Option[Array[Float]])
