package com.keepit.search.spellcheck

import scala.util.Random
import scala.math.{ exp, log => logE }
import com.keepit.common.logging.Logging

// enableAdjScore: terms closer to each other get boosted
// orderedAdj: term should appear in right order
class TermScorer(statsReader: TermStatsReader, enableAdjScore: Boolean, orderedAdj: Boolean = false) extends Logging {

  private var statsMap = Map.empty[String, SimpleTermStats]
  private var jointMap = Map.empty[(String, String), Set[Int]]
  private var adjScoreMap = Map.empty[(String, String), Float]

  val rand = new Random()
  val SIGMA = 2
  val MIN_ADJ_SCORE = 0.001f
  val SAMPLE_SIZE = 50

  def minPairTermsScore: Float = if (!enableAdjScore) 1f else MIN_ADJ_SCORE

  private def log2(x: Double) = logE(x) / logE(2)

  def gaussianScore(x: Float) = {
    exp(-x * x / (2 * SIGMA * SIGMA)) max MIN_ADJ_SCORE // this close to 0 when x > 3*SIGMA. Add a smoother
  }

  def warmUpStatsMap(words: Set[String]) = words.foreach { getOrUpdateStats(_) }

  private def getOrUpdateStats(word: String) = statsMap.getOrElse(word,
    { val stat = statsReader.getSimpleTermStats(word); statsMap += (word -> stat); stat }
  )

  private def getOrUpdateJoint(key: (String, String)) = jointMap.getOrElse(key, {
    val (a, b) = key
    val (aStat, bStat) = (getOrUpdateStats(a), getOrUpdateStats(b))
    val inter = aStat.docIds intersect bStat.docIds
    jointMap += key -> inter
    inter
  }
  )

  private def getOrUpdateAdjScore(key: (String, String), inter: Set[Int]) = adjScoreMap.getOrElse(key, {
    val (a, b) = key
    val score = adjacencyScore(a, b, inter)
    adjScoreMap += key -> score
    score
  })

  private def adjacencyScore(a: String, b: String, inter: Set[Int]): Float = {
    if (inter.isEmpty) return MIN_ADJ_SCORE
    log.info(s"adjScore: ${a}, ${b}, inter size: ${inter.size}")
    val subset = if (inter.size <= SAMPLE_SIZE) inter else rand.shuffle(inter).take(SAMPLE_SIZE)
    val liveDocs = TermStatsReader.genBits(subset)
    val (aMap, bMap) = (statsReader.getDocsAndPositions(a, liveDocs), statsReader.getDocsAndPositions(b, liveDocs))
    assume(aMap.keySet == bMap.keySet)
    val scorer = new AdjacencyScorer
    val dists = aMap.keySet.map { k => scorer.distance(aMap(k), bMap(k), earlyStopValue = 1, orderedAdj) }
    log.info(s"adjScore: ${a}, ${b}, distances: ${dists.mkString(" ")}")
    val minDist = dists.foldLeft(Float.MaxValue)(_ min _)
    log.info(s"adjScore: ${a}, ${b}, min dist: ${minDist}, orderedAdj: ${orderedAdj}")
    gaussianScore(minDist - 1).toFloat
  }

  def scoreSingleTerm(term: String): Float = {
    val termStats = getOrUpdateStats(term)
    val s = log2(1.0 + termStats.docFreq).toFloat * termStats.idf
    log.info(s"TermScorer: ${term} ${s}. docFreq = ${termStats.docFreq}, idf = ${termStats.idf}")
    s
  }

  def scorePairTerms(a: String, b: String): Float = {
    val key = if (a <= b) (a, b) else (b, a)
    val inter = getOrUpdateJoint(key)
    val pairScore = log2(1.0 + inter.size.max(1)).toFloat // smooth
    val adjBoost = if (enableAdjScore) {
      if (!orderedAdj) getOrUpdateAdjScore(key, inter) else getOrUpdateAdjScore((a, b), inter)
    } else 1f
    log.info(s"TermScorer: ${a}, ${b}, pairFreqScore = ${pairScore}, adjBoost = ${adjBoost}")
    pairScore * adjBoost
  }

  def scoreTripleTerms(a: String, b: String, c: String): Float = {
    val inter = getOrUpdateJoint(a, b) intersect getOrUpdateStats(c).docIds
    log.info(s"TermScorer: ${a}, ${b}, ${c} intersection freq: ${inter.size}")
    log2(1 + inter.size).toFloat max 0.01f // smooth
  }
}
