package com.keepit.search.engine

abstract class ScoreExpr { // using abstract class for performance. trait is slower.
  def apply()(implicit hitJoiner: ScoreContext): Float

  def isNullExpr: Boolean = false
}

// Null Expr
object NullExpr extends ScoreExpr {
  def apply()(implicit hitJoiner: ScoreContext): Float = 0.0f
  override def isNullExpr: Boolean = true
}

object MaxExpr {

  class MaxExpr(index: Int) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      hitJoiner.scoreMax(index)
    }
  }

  def apply(index: Int): ScoreExpr = new MaxExpr(index)
}

object MaxWithTieBreakerExpr {

  class MaxWithTieBreakerExpr(index: Int, tieBreakerMultiplier: Float) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      val scoreMax = hitJoiner.scoreMax(index)
      scoreMax + (hitJoiner.scoreSum(index) - scoreMax) * tieBreakerMultiplier
    }
  }

  def apply(index: Int, tieBreakerMultiplier: Float): ScoreExpr = new MaxWithTieBreakerExpr(index, tieBreakerMultiplier)
}

// Disjunctive Sum
object DisjunctiveSumExpr {

  class DisjunctiveSumExpr(elems: Array[ScoreExpr]) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      var sum: Float = 0.0f
      var i = 0
      while (i < elems.length) { // using while for performance
        sum += elems(i).apply()
        i += 1
      }
      sum
    }
  }

  def apply(elems: Seq[ScoreExpr]): ScoreExpr = {
    elems.size match {
      case 0 => NullExpr
      case 1 => elems.head
      case _ => new DisjunctiveSumExpr(elems.toArray)
    }
  }
}

// Conjunctive Sum
object ConjunctiveSumExpr {

  class ConjunctiveSumExpr(elems: Array[ScoreExpr]) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      var sum: Float = 0.0f
      var i = 0
      while (i < elems.length) { // using while for performance
        val elemScore = elems(i).apply()
        if (elemScore <= 0.0f) return 0.0f
        sum += elemScore
        i += 1
      }
      sum
    }
  }

  def apply(elems: Seq[ScoreExpr]): ScoreExpr = {
    elems.size match {
      case 0 => NullExpr
      case 1 => elems.head
      case _ => new ConjunctiveSumExpr(elems.toArray)
    }
  }
}

// Exists
object ExistsExpr {

  class ExistsExpr(elems: Array[ScoreExpr]) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      var i = 0
      while (i < elems.length) { // using while for performance
        if (elems(i).apply() > 0.0f) return 1.0f
        i += 1
      }
      0.0f
    }
  }

  def apply(elems: Seq[ScoreExpr]): ScoreExpr = {
    elems.size match {
      case 0 => NullExpr
      case 1 => elems.head
      case _ => new ExistsExpr(elems.toArray)
    }
  }
}

// For All
object ForAllExpr {

  class ForAllExpr(elems: Array[ScoreExpr]) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      var i = 0
      while (i < elems.length) { // using while for performance
        val elemScore = elems(i).apply()
        if (elemScore <= 0.0f) return 0.0f
        i += 1
      }
      1.0f
    }
  }

  def apply(elems: Seq[ScoreExpr]): ScoreExpr = {
    elems.size match {
      case 0 => NullExpr
      case 1 => elems.head
      case _ => new ForAllExpr(elems.toArray)
    }
  }
}

// Boolean
object BooleanExpr {

  class BooleanExpr(optional: ScoreExpr, required: ScoreExpr) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      val score = required()
      if (score > 0.0f) score + optional() else 0.0f
    }
  }

  def apply(optional: ScoreExpr, required: ScoreExpr): ScoreExpr = {
    if (required.isNullExpr) optional else new BooleanExpr(optional, required)
  }
}

// Filter
object FilterExpr {

  class FilterExpr(expr: ScoreExpr, filter: ScoreExpr) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      if (filter() > 0.0f) expr() else 0.0f
    }
  }

  def apply(expr: ScoreExpr, filter: ScoreExpr): ScoreExpr = {
    if (expr.isNullExpr) filter else if (filter.isNullExpr) expr else new FilterExpr(expr, filter)
  }
}

// Filter Out
object FilterOutExpr {

  class FilterOutExpr(textScore: ScoreExpr, filterOut: ScoreExpr) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      if (filterOut() <= 0.0f) textScore() else 0.0f
    }
  }

  def apply(expr: ScoreExpr, filter: ScoreExpr): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else if (filter.isNullExpr) expr else new FilterOutExpr(expr, filter)
  }
}

// Boost
object BoostExpr {

  class BoostExpr(expr: ScoreExpr, booster: ScoreExpr, boostStrength: Float) extends ScoreExpr {
    def apply()(implicit hitJoiner: ScoreContext): Float = {
      expr() * (booster() * boostStrength + (1.0f - boostStrength))
    }
  }

  def apply(expr: ScoreExpr, booster: ScoreExpr, boostStrength: Float): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else if (booster.isNullExpr) expr else new BoostExpr(expr, booster, boostStrength)
  }
}
