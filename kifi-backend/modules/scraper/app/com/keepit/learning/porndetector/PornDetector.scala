package com.keepit.learning.porndetector

import scala.math.{log, exp}

trait PornDetector {
  def posterior(text: String): Float          // probability being porn
  def isPorn(text: String): Boolean
}

/**
 * likelihoodRatio: prob(word | porn) / prob(word | non-porn)
 * priorOfPorn: prior probability of porn
 * oovRatio: likelihoodRatio for out-of-vocabulary token
 * This is meant to be a detector for short text (e.g  less than 10 tokens). For bigger text, use sliding window to detect "porn part"
 */

class NaiveBayesPornDetector(
  likelihoodRatio: Map[String, Float],
  priorOfPorn: Float = 0.5f,
  oovRatio: Float = 0.001f
) extends PornDetector {

  private def logPosteriorRatio(text: String): Double = {
    PornDetectorUtil.tokenize(text).foldLeft(0.0){ case (score, token) =>
      score + log(likelihoodRatio.getOrElse(token, oovRatio).toDouble)
    } + log(priorOfPorn.toDouble / (1 - priorOfPorn).toDouble)
  }

  override def posterior(text: String): Float = {
    val logProb = logPosteriorRatio(text)
    if (logProb > 10) 1f
    else if (logProb < -10) 0f
    else { val ratio = exp(logProb); (ratio/(ratio + 1)).toFloat }
  }

  override def isPorn(text: String): Boolean = logPosteriorRatio(text) >= 0f
}

object PornDetectorUtil {
  def tokenize(text: String): Array[String] = {
    text.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map{_.toLowerCase}
  }
}
