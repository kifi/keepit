package com.keepit.search.engine

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.strings.Profanity
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.index.sharding.ActiveShards
import org.apache.lucene.index.Term

@Singleton
class LibraryQualityEvaluator @Inject() (activeShards: ActiveShards) {

  // todo(Léo): Use library kind?

  def getInverseLibraryFrequencyBoost(keepSearcher: Searcher, libId: Long): Float = {
    // number of keeps from this shard in this library (correlated with total number of keeps in this library)
    val keepCount = keepSearcher.freq(new Term(KeepFields.libraryField, libId.toString))
    logDecreasingScore(1, keepCount).toFloat
  }

  def getPopularityBoost(librarySearcher: Searcher, libId: Long): Float = {
    val memberCount = librarySearcher.getLongDocValue(LibraryFields.allUsersCountField, libId) getOrElse 1L
    1 - logDecreasingScore(1, memberCount).toFloat
  }

  private def logDecreasingScore(rate: Double, x: Double): Double = 1.0 / (1 + Math.log(1 + rate * x))

  def getPublishedLibraryBoost(keepSearcher: Searcher, libId: Long): Float = {
    val keepCount = keepSearcher.freq(new Term(KeepFields.libraryField, libId.toString))
    (logNormalDensity(mu, sigma, keepCount) / maxProbability).toFloat
  }

  // See http://www.nowherenearithaca.com/2013/12/equationlognormalhover-border1px-solid.html
  private val optimalLibrarySize = 70 // this is it
  private val mode = optimalLibrarySize / activeShards.all.size // assuming uniform distribution of a library's keeps across all shards
  private val sigma = 1.0 // eyeballed for reasonable decay
  private val mu = Math.log(mode + Math.pow(sigma, 2))
  private val maxProbability = logNormalDensity(mu, sigma, mode)

  @inline private def logNormalDensity(mu: Double, sigma: Double, x: Double): Double = {
    Math.exp(-Math.pow(Math.log(x) - mu, 2) / (2 * Math.pow(sigma, 2))) / (x * sigma * Math.sqrt(2 * Math.PI))
  }

  private val LowQualityLibraryNamesRe = "(?i)(test|delicious|bookmark|pocket|kippt|asdf|pinboard|import|instapaper)".r
  def isPoorlyNamed(name: String): Boolean = {
    LowQualityLibraryNamesRe.findFirstIn(name).isDefined || name.toLowerCase.split("\\s+").exists(Profanity.all.contains)
  }
}
