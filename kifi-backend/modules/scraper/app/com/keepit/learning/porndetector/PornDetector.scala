package com.keepit.learning.porndetector

import com.keepit.common.logging.Logging

import scala.math.{ log, exp }

trait PornDetector {
  def posterior(text: String): Float // probability being porn
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
    oovRatio: Float = 0.001f) extends PornDetector{

  private def logPosteriorRatio(text: String): Double = {
    PornDetectorUtil.tokenize(text).foldLeft(0.0) {
      case (score, token) =>
        score + log(likelihoodRatio.getOrElse(token, oovRatio).toDouble)
    } + log(priorOfPorn.toDouble / (1 - priorOfPorn).toDouble)
  }

  override def posterior(text: String): Float = {
    val logProb = logPosteriorRatio(text)
    if (logProb > 10) 1f
    else if (logProb < -10) 0f
    else { val ratio = exp(logProb); (ratio / (ratio + 1)).toFloat }
  }

  override def isPorn(text: String): Boolean = posterior(text) >= 0.75f // shifted threshold
}

class SlidingWindowPornDetector(detector: PornDetector, windowSize: Int = 10) extends PornDetector with Logging{
  if (windowSize <= 4) throw new IllegalArgumentException(s"window size for SlidingWindowPornDetector too small: get ${windowSize}, need at least 4")
  def detectBlocks(text: String): (Int, Int) = {
    val blocks = PornDetectorUtil.tokenize(text).sliding(windowSize, windowSize).toArray
    val bad = blocks.filter { b => detector.isPorn(b.mkString(" ")) }
    (blocks.size, bad.size)
  }

  override def isPorn(text: String): Boolean = posterior(text) > 0.5f

  override def posterior(text: String): Float = {
    log.info(s"[SlidingWindowPornDetector]: detecting for: ${text.take(100)}")
    val (blocks, badBlocks) = detectBlocks(text)
    if (blocks == 0) return 0f
    val r = badBlocks / blocks.toFloat
    log.info(s"[SlidingWindowPornDetector]: ratio = ${r}, num of bad blocks: ${badBlocks}")
    if (r > 0.05 || badBlocks > 10) return 1f else 0f // not smooth (for performance reason). could use more Bayesian style
  }
}

object PornDetectorUtil {
  def tokenize(text: String): Array[String] = {
    text.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map { _.toLowerCase }
  }
}
