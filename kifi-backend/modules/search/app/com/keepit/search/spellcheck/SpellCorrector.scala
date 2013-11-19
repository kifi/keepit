package com.keepit.search.spellcheck

import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.apache.lucene.analysis.standard.StandardAnalyzer
import scala.util.Random
import scala.math.exp

@ImplementedBy(classOf[SpellCorrectorImpl])
trait SpellCorrector {
  def getSuggestion(input: String): String
  def getSuggestions(input: String, numSug: Int): Array[String]
  def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest]
}

class SpellCorrectorImpl(spellIndexer: SpellIndexer, enableAdjScore: Boolean) extends SpellCorrector{
  val spellChecker = spellIndexer.getSpellChecker
  val stopwords = StandardAnalyzer.STOP_WORDS_SET

  override def getSuggestion(input: String): String = {
    val terms = input.trim().split(" ")
    terms.map{t => getSimilarTerms(t, 1).head }.mkString(" ")
  }

  override def getSuggestions(input: String, numSug: Int): Array[String] = {
    val terms = input.trim().split(" ")
    val variations = terms.map{ getSimilarTerms(_, numSug.min(10))}.toList        // need a limit here
    val paths = getPaths(variations)
    paths.map{path => path.mkString(" ")}
  }

  override def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest] = {
    val suggestions = getSuggestions(input, numSug.min(10)).map{Suggest(_)}
    val scorer = new SuggestionScorer(spellIndexer.getTermStatsReader, enableAdjScore)
    scorer.rank(input, suggestions, enableBoost)
  }

  // exponential. Use Viterbi-like algorithm later. (Need to support k-best paths)
  private def getPaths(variations: List[Array[String]]): Array[Array[String]] = {
    variations match {
      case head::tail => {
        val paths = getPaths(tail)
        if (paths.isEmpty) head.map{ x => Array(x) }
        else for { x <- head ; path <- paths } yield { x +: path }
      }
      case Nil => Array()
    }
  }

  def getSimilarTerms(term: String, numSug: Int): Array[String] = {
    val similar = spellChecker.suggestSimilar(term, numSug)       // this never includes the original term
    if (spellChecker.exist(term) || stopwords.contains(term) || similar.isEmpty) Array(term) ++ similar.take(2)   // add 2 just in case misspelling words were indexed
    else similar
  }
}

class MetaphoneBooster {
  val mdist = new MetaphoneDistance()
  def similarity(a: Array[String], b: Array[String]): Float = {
    (a zip b).map{ case (x, y) => mdist.getDistance(x, y) }.foldLeft(1f)(_*_)
  }
}

class ScoreDecorator(originalQuery: String) {
  val booster = new MetaphoneBooster
  val originalTerms = originalQuery.split(" ")
  def decorate(scoredSuggest: ScoredSuggest): ScoredSuggest = {
    val boost = booster.similarity(originalTerms, scoredSuggest.value.split(" "))
    ScoredSuggest(value = scoredSuggest.value, score = boost * scoredSuggest.score)
  }
}

class SuggestionScorer(statsReader: TermStatsReader, enableAdjScore: Boolean) {

  private var statsMap = Map.empty[String, SimpleTermStats]
  private var jointMap = Map.empty[(String, String), Set[Int]]
  private var adjScoreMap = Map.empty[(String, String), Float]

  val rand = new Random()
  val SIGMA = 5
  val MIN_ADJ_SCORE = 0.01f

  def gaussianScore(x: Float) = {
    exp(-x*x/(2*SIGMA*SIGMA)) max MIN_ADJ_SCORE      // with sigma = 5, this close to 0 when x > 10. Add a smoother
  }

  def warmUpStatsMap(words: Set[String]) = words.foreach{getOrUpdateStats(_)}

  private def getOrUpdateStats(word: String) = statsMap.getOrElse( word,
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
    val subset = if (inter.size <= 10 ) inter else rand.shuffle(inter).take(10)     // sample
    val liveDocs = TermStatsReader.genBits(subset)
    val (aMap, bMap) = (statsReader.getDocsAndPositions(a, liveDocs), statsReader.getDocsAndPositions(b, liveDocs))
    assume (aMap.keySet == bMap.keySet)
    val scorer = new AdjacencyScorer
    val dists = aMap.keySet.map{k => scorer.distance(aMap(k), bMap(k))}
    val avgDist = dists.foldLeft(0)(_+_)/(dists.size).toFloat       // take average ( or median? )
    gaussianScore(avgDist).toFloat
  }

  def score(suggest: Suggest): ScoredSuggest = {
    if (suggest.value.trim == "") return ScoredSuggest("", 0)
    val words = suggest.value.split(" ")
    if (words.size == 1) {
      val score = getOrUpdateStats(words.head).docFreq
      ScoredSuggest(suggest.value, score)
    } else {
      val pairs = words.sliding(2, 1)
      var score = 1f
      pairs.foreach{ case Array(a, b) =>
        val key = if (a <= b) (a,b) else (b, a)
        val inter = getOrUpdateJoint(key)
        val pairScore = inter.size.max(1)   // smooth
        val adjBoost = if (enableAdjScore) {
          getOrUpdateAdjScore(key, inter)
        } else 1f
        score *= pairScore * adjBoost
      }
      ScoredSuggest(suggest.value, score)
    }
  }

  def rank(input: String, suggests: Array[Suggest], enableBoost: Boolean): Array[ScoredSuggest] = {
    val scored = {
      if (!enableBoost) suggests.map{score(_)}
      else {
        val deco = new ScoreDecorator(input)
        suggests.map{score(_)}.map{deco.decorate(_)}
      }
    }
    scored.sortBy(_.score*(-1.0))
  }
}
