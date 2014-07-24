package com.keepit.search.engine

abstract class ScoreExpression { // using abstract class for performance. trait is slower.
  def apply()(implicit hitJoiner: ScoreContext): Float
}

class MaxExpr(index: Int) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    hitJoiner.scoreMax(index)
  }
}

class MaxWithTieBreakerExpr(index: Int, tieBreakerMultiplier: Float) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    val scoreMax = hitJoiner.scoreMax(index)
    scoreMax + (hitJoiner.scoreSum(index) - scoreMax) * tieBreakerMultiplier
  }
}

class DisjunctiveSumExpr(elem: Array[ScoreExpression]) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    var sum: Float = 0.0f
    var i = 0
    while (i < elem.length) { // using while for performance
      sum += elem(i).apply()
      i += 1
    }
    sum
  }
}

class ConjunctiveSumExpr(elem: Array[ScoreExpression]) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    var sum: Float = 0.0f
    var i = 0
    while (i < elem.length) { // using while for performance
      val elemScore = elem(i).apply()
      if (elemScore <= 0.0f) return 0.0f
      sum += elemScore
      i += 1
    }
    sum
  }
}

class ExistsExpr(elem: Array[ScoreExpression]) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    var i = 0
    while (i < elem.length) { // using while for performance
      if (elem(i).apply() > 0.0f) 1.0f
      i += 1
    }
    0.0f
  }
}

class BooleanExpr(textScore: ScoreExpression, required: ScoreExpression) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    val score = required()
    if (score > 0.0f) score + textScore() else 0.0f
  }
}

class BooleanNotExpr(textScore: ScoreExpression, prohibited: ScoreExpression) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    if (prohibited() <= 0.0f) textScore() else 0.0f
  }
}

class BoostExpr(textScore: ScoreExpression, booster: ScoreExpression, boostStrength: Float) extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = {
    textScore() * (booster() * boostStrength + (1.0f - boostStrength))
  }
}

object NullExpr extends ScoreExpression {
  def apply()(implicit hitJoiner: ScoreContext): Float = 0.0f
}

