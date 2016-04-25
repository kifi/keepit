package com.keepit.search.engine

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.strings.Profanity

@Singleton
class LibraryQualityEvaluator @Inject() () {

  // todo(Léo): Use library kind?

  def getInverseLibraryFrequencyBoost(keepCount: Long): Float = logDecreasingScore(1, keepCount).toFloat
  def getPopularityBoost(memberCount: Long): Float = 1 - logDecreasingScore(1, memberCount).toFloat
  def getPublishedLibraryBoost(keepCount: Long): Float = (logNormalDensity(mu, sigma, keepCount) / maxProbability).toFloat

  private def logDecreasingScore(rate: Double, x: Double): Double = if (x < 0 || rate < 0) 0 else 1.0 / (1 + Math.log(1 + rate * x))

  // See http://www.nowherenearithaca.com/2013/12/equationlognormalhover-border1px-solid.html
  private val optimalLibrarySize = 70 // this is it
  private val sigma = 1.0 // eyeballed for reasonable decay
  private val mu = Math.log(optimalLibrarySize) + Math.pow(sigma, 2)
  private val maxProbability = logNormalDensity(mu, sigma, optimalLibrarySize)

  @inline private def logNormalDensity(mu: Double, sigma: Double, x: Double): Double = if (x > 0) {
    Math.exp(-Math.pow(Math.log(x) - mu, 2) / (2 * Math.pow(sigma, 2))) / (x * sigma * Math.sqrt(2 * Math.PI))
  } else 0

  private val LowQualityLibraryNamesRe = "(?i)(test|delicious|bookmark|pocket|kippt|asdf|pinboard|import|imported|instapaper)".r
  def isPoorlyNamed(name: String): Boolean = {
    LowQualityLibraryNamesRe.findFirstIn(name).isDefined || name.toLowerCase.split("\\s+").exists(Profanity.all.contains)
  }
}
