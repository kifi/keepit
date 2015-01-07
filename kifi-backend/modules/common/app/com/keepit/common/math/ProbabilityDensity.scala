package com.keepit.common.math

import play.api.libs.functional.syntax._
import play.api.libs.json.{ JsNumber, JsArray, Json, Format }
import scala.collection.mutable

case class Probability[A](outcome: A, probability: Double)

//Note: The order of the argument Seq here is very important. It, along with the salt, uniquely determines which users go in which bin.
class ProbabilityDensity[A](private val cumulative: Array[Probability[A]]) {

  def density: Seq[Probability[A]] = {
    var cdf = 0.0
    cumulative.toSeq.map { p =>
      val probability = Probability(p.outcome, p.probability - cdf)
      cdf = p.probability
      probability
    }
  }

  def sample(x: Double): Option[A] = if (cumulative.length < 32) linearSample(x) else binarySample(x) // solve x = 8 * log2(x)

  def linearSample(x: Double): Option[A] = cumulative.collectFirst { case Probability(outcome, cdf) if x <= cdf => outcome }
  def binarySample(x: Double): Option[A] = {
    val n = cumulative.length
    var low = 0
    var up = n
    var mid = 0
    while (low < up) {
      mid = low + (up - low) / 2
      if (x > cumulative(mid).probability) low = mid + 1
      else up = mid
    }
    if (low < n) Some(cumulative(low).outcome) else None
  }

  override def hashCode(): Int = cumulative.toSeq.hashCode()
  override def equals(other: Any): Boolean = {
    other match {
      case otherProbabilityDensity: ProbabilityDensity[_] => cumulative.toSeq == otherProbabilityDensity.cumulative.toSeq
      case _ => false
    }
  }
}

object ProbabilityDensity {
  def format[A](implicit outcomeFormat: Format[A]): Format[ProbabilityDensity[A]] = Json.format[JsArray].inmap(

    {
      case JsArray(density) => ProbabilityDensity(
        density.sliding(2, 2).map { case Seq(outcome, JsNumber(probability)) => Probability(outcome.as[A], probability.toDouble) }.toSeq
      )
    },

    { density: ProbabilityDensity[A] =>
      JsArray(
        density.density.flatMap { case Probability(outcome, probability) => Seq(Json.toJson(outcome), JsNumber(probability)) }
      )
    }
  )

  def apply[A](density: Seq[Probability[A]]): ProbabilityDensity[A] = apply(density, 1.0)

  private[math] def apply[A](density: Seq[Probability[A]], normalizer: Double): ProbabilityDensity[A] = {
    require(density.forall(_.probability >= 0), "Probabilities must be non-negative")
    require(density.map(_.probability).sum / normalizer <= 1.1, "Probabilities sum up to more than 1")
    val cumulative: Array[Probability[A]] = { // The order of the density sequence is implied to compute the CDF
      var sum = 0.0
      density.collect {
        case Probability(outcome, probability) if probability > 0 =>
          sum += probability
          Probability(outcome, sum / normalizer)
      }.toArray
    }
    new ProbabilityDensity(cumulative)
  }
}

class ProbabilityDensityBuilder[A] {
  private[this] val buf = new mutable.ListBuffer[Probability[A]]
  private[this] var sum = 0.0

  def add(outcome: A, weight: Double): Unit = {
    if (weight > 0) {
      buf += Probability(outcome, weight)
      sum += weight
    }
  }

  def build(): ProbabilityDensity[A] = {
    if (sum > 0.0) ProbabilityDensity(buf, sum) else ProbabilityDensity(buf)
  }
}
