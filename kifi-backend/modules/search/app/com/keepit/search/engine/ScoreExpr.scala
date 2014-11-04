package com.keepit.search.engine

abstract class ScoreExpr { // using abstract class for performance. trait is slower.
  def apply()(implicit ctx: ScoreContext): Float
  def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit

  def isNullExpr: Boolean = false
  def isLeafExpr: Boolean = false

  protected def explain(operatorName: String)(implicit sb: StringBuilder, ctx: ScoreContext): Unit = {
    val value = apply()
    sb.append(s"<li>$operatorName: $value</li>\n")
  }
  protected def explain(operatorName: String, operand1: ScoreExpr, operandName1: String, operand2: ScoreExpr, operandName2: String)(implicit sb: StringBuilder, ctx: ScoreContext): Unit = {
    val value = apply()
    sb.append(s"<li>$operatorName: $value</li>\n")
    sb.append("  <ul>\n")
    sb.append(s"  <li> $operandName1:</li>\n")
    sb.append("      <ul>\n")
    operand1.explain()
    sb.append("      </ul>\n")
    sb.append(s"  <li> $operandName2:</li>\n")
    sb.append("    <ul>\n")
    operand2.explain()
    sb.append("    </ul>\n")
    sb.append("  </ul>\n")
  }
  protected def explain(operatorName1: String, operands: Array[ScoreExpr])(implicit sb: StringBuilder, ctx: ScoreContext): Unit = {
    val value = apply()
    sb.append(s"<li>$operatorName1: $value</li>\n")
    sb.append("  <ul>\n")
    operands.foreach { operand =>
      operand.explain()
    }
    sb.append("  </ul>\n")
  }
}

// Null Expr
object NullExpr extends ScoreExpr {
  def apply()(implicit ctx: ScoreContext): Float = 0.0f
  def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = sb.append("<li>Null</li>\n")

  override def isNullExpr: Boolean = true
  override def isLeafExpr: Boolean = true

  override def toString(): String = "Null"
}

object MaxExpr {

  class MaxExpr(index: Int) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      ctx.scoreMax(index)
    }
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("MaxExpr")
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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("SumExpr")
    override def isLeafExpr: Boolean = true
    override def toString(): String = s"Sum($index)"
  }

  def apply(index: Int): ScoreExpr = new SumExpr(index)
}

object MaxWithTieBreakerExpr {

  class MaxWithTieBreakerExpr(index: Int, tieBreakerMultiplier: Float) extends ScoreExpr {
    def apply()(implicit ctx: ScoreContext): Float = {
      val scoreMax = ctx.scoreMax(index)
      val scoreSum = ctx.scoreSum(index)
      if (scoreMax == scoreSum) {
        scoreMax
      } else {
        scoreMax * (tieBreakerMultiplier * (1.0f - (scoreMax / scoreSum)) + 1.0f)
      }
    }
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = {
      val value = apply()
      sb.append(s"<li>MaxWithTieBreakerExpr: $value</li>\n")
      sb.append(s"  <ul><li>max=${ctx.scoreMax(index)}, sum=${ctx.scoreSum(index)}</li></ul>\n")
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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("DisjunctiveSumExpr", elems)

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("ConjunctiveSumExpr", elems)

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("ExistsExpr", elems)

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("ForAllExpr", elems)

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("BooleanExpr", optional, "optional", required, "required")

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("FilterExpr", expr, "expr", filter, "filter")

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("FilterOutExpr", expr, "expr", filter, "filter")

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
    def explain()(implicit sb: StringBuilder, ctx: ScoreContext): Unit = explain("BoostExpr", expr, "expr", booster, "booster")

    override def toString(): String = s"Boost(${expr.toString}, ${booster.toString}, $boostStrength)"
  }

  def apply(expr: ScoreExpr, booster: ScoreExpr, boostStrength: Float): ScoreExpr = {
    if (expr.isNullExpr) NullExpr else if (booster.isNullExpr || boostStrength <= 0.0f) expr else new BoostExpr(expr, booster, boostStrength)
  }
}
