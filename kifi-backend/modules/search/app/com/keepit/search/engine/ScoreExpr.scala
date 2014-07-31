package com.keepit.search.engine

abstract class ScoreExpr { // using abstract class for performance. trait is slower.
  def apply()(implicit ctx: ScoreContext): Float

  def isNullExpr: Boolean = false
  def isLeafExpr: Boolean = false
}

// Null Expr
object NullExpr extends ScoreExpr {
  def apply()(implicit ctx: ScoreContext): Float = 0.0f
  override def isNullExpr: Boolean = true
  override def isLeafExpr: Boolean = true

  override def toString(): String = "Null"
}

object MaxExpr {

  class MaxExpr(index: Int) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      ctx.scoreMax(index)
    }
    override def isLeafExpr: Boolean = true
    override def toString(): String = s"Max($index)"
  }

  def apply(index: Int): ScoreExpr = new MaxExpr(index)
}

object SumExpr {

  class SumExpr(index: Int) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      ctx.scoreSum(index)
    }
    override def isLeafExpr: Boolean = true
    override def toString(): String = s"Sum($index)"
  }

  def apply(index: Int): ScoreExpr = new SumExpr(index)
}

object MaxWithTieBreakerExpr {

  class MaxWithTieBreakerExpr(index: Int, tieBreakerMultiplier: Float) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      val scoreMax = ctx.scoreMax(index)
      scoreMax + (ctx.scoreSum(index) - scoreMax) * tieBreakerMultiplier / ctx.norm
    }
    override def isLeafExpr: Boolean = true
    override def toString(): String = s"MaxWithTieBreaker($index, $tieBreakerMultiplier)"
  }

  def apply(index: Int, tieBreakerMultiplier: Float): ScoreExpr = new MaxWithTieBreakerExpr(index, tieBreakerMultiplier)
}

// Disjunctive Sum
object DisjunctiveSumExpr {

  class DisjunctiveSumExpr(elems: Array[ScoreExpr]) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      var sum: Float = 0.0f
      var i = 0
      while (i < elems.length) { // using while for performance
        sum += elems(i).apply()
        i += 1
      }
      sum
    }
    override def toString(): String = s"DisjunctiveSum(${elems.map(_.toString).mkString(", ")})"
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
    def apply()(implicit ctx: ScoreContext): Float = {
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
    override def toString(): String = s"ConjunctiveSum(${elems.map(_.toString).mkString(", ")})"
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
    def apply()(implicit ctx: ScoreContext): Float = {
      var i = 0
      while (i < elems.length) { // using while for performance
        if (elems(i).apply() > 0.0f) return 1.0f
        i += 1
      }
      0.0f
    }
    override def toString(): String = s"Exists(${elems.map(_.toString).mkString(", ")})"
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
    def apply()(implicit ctx: ScoreContext): Float = {
      var i = 0
      while (i < elems.length) { // using while for performance
        val elemScore = elems(i).apply()
        if (elemScore <= 0.0f) return 0.0f
        i += 1
      }
      1.0f
    }
    override def toString(): String = s"ForAll(${elems.map(_.toString).mkString(", ")})"
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
    def apply()(implicit ctx: ScoreContext): Float = {
      val score = required()
      if (score > 0.0f) score + optional() else 0.0f
    }
    override def toString(): String = s"Boolean(${optional.toString}, ${required.toString})"
  }

  def apply(optional: ScoreExpr, required: ScoreExpr): ScoreExpr = {
    if (required.isNullExpr) optional else new BooleanExpr(optional, required)
  }
}

// Filter
object FilterExpr {

  class FilterExpr(expr: ScoreExpr, filter: ScoreExpr) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      if (filter() > 0.0f) expr() else 0.0f
    }
    override def toString(): String = s"Filter(${expr.toString}, ${filter.toString})"
  }

  def apply(expr: ScoreExpr, filter: ScoreExpr): ScoreExpr = {
    if (expr.isNullExpr) filter else if (filter.isNullExpr) expr else new FilterExpr(expr, filter)
  }
}

// Filter Out
object FilterOutExpr {

  class FilterOutExpr(expr: ScoreExpr, filter: ScoreExpr) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      if (filter() <= 0.0f) expr() else 0.0f
    }
    override def toString(): String = s"FilterOut(${expr.toString}, ${filter.toString})"
  }

  def apply(expr: ScoreExpr, filter: ScoreExpr): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else if (filter.isNullExpr) expr else new FilterOutExpr(expr, filter)
  }
}

// Boost
object BoostExpr {

  class BoostExpr(expr: ScoreExpr, booster: ScoreExpr, boostStrength: Float) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      expr() * (booster() * boostStrength + (1.0f - boostStrength))
    }
    override def toString(): String = s"Boost(${expr.toString}, ${booster.toString}, $boostStrength)"
  }

  def apply(expr: ScoreExpr, booster: ScoreExpr, boostStrength: Float): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else if (booster.isNullExpr || boostStrength <= 0.0f) expr else new BoostExpr(expr, booster, boostStrength)
  }
}

// Percent Match
object PercentMatchExpr {

  class PercentMatchExpr(expr: ScoreExpr, threshold: Float) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      var pct = 1.0f
      var i = 0
      val scoreMax = ctx.scoreMax
      val matchWeight = ctx.matchWeight
      val len = scoreMax.length
      while (i < len) { // using while for performance
        if (scoreMax(i) <= 0.0f) {
          pct -= matchWeight(i)
          if (pct < threshold) return 0.0f
        }
        i += 1
      }
      expr() * pct
    }
    override def toString(): String = s"PercentMatch(${expr.toString}, $threshold)"
  }

  def apply(expr: ScoreExpr, threshold: Float): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else {
      // if threshold is non-negative, apply percent match even when threshold is zero because
      // we still want to apply coord factor. set threshold to negative to disable it.
      if (threshold < 0.0f || expr.isLeafExpr) expr else new PercentMatchExpr(expr, threshold)
    }
  }
}
