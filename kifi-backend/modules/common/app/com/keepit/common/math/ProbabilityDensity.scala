package com.keepit.common.math

import play.api.libs.functional.syntax._
import play.api.libs.json.{ JsNumber, JsArray, Json, Format }

//Note: The order of the argument Seq here is very important. It, along with the salt, uniquely determines which users go in which bin.
case class ProbabilityDensity[+A](density: Seq[(A, Double)]) {
  require(density.forall(_._2 >= 0), "Probabilities must be non-negative")
  require(density.map(_._2).sum <= 1.1, "Probabilities sum up to more than 1")
  private val cumulative: Seq[(A, Double)] = { // The order of the density sequence is implied to compute the CDF
    var cdf = 0.0
    density.collect {
      case (outcome, probability) if probability > 0 =>
        cdf += probability
        outcome -> cdf
    }
  }

  def sample(x: Double): Option[A] = cumulative.collectFirst { case (outcome, cdf) if x <= cdf => outcome }
}

object ProbabilityDensity {
  def format[A](implicit outcomeFormat: Format[A]): Format[ProbabilityDensity[A]] = Json.format[JsArray].inmap(

    {
      case JsArray(density) => ProbabilityDensity(
        density.sliding(2, 2).map { case Seq(outcome, JsNumber(probability)) => (outcome.as[A], probability.toDouble) }.toSeq
      )
    },

    { density: ProbabilityDensity[A] =>
      JsArray(
        density.density.flatMap { case (outcome, probability) => Seq(Json.toJson(outcome), JsNumber(probability)) }
      )
    }
  )

  def normalized[A](density: Seq[(A, Double)]): ProbabilityDensity[A] = {
    val sum = density.map { case (outcome, probability) => probability }.sum
    if (sum == 0) ProbabilityDensity(density) else {
      val normalizedDensity = density.map { case (outcome, probability) => outcome -> probability / sum }
      ProbabilityDensity(normalizedDensity)
    }
  }
}
