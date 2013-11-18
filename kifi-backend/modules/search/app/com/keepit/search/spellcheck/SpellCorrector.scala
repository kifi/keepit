package com.keepit.search.spellcheck

import com.google.inject.{ImplementedBy, Inject, Singleton}


@ImplementedBy(classOf[SpellCorrectorImpl])
trait SpellCorrector {
  def getSuggestion(input: String): String
  def getSuggestions(input: String, numSug: Int): Array[String]
  def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest]
}

@Singleton
class SpellCorrectorImpl @Inject()(spellIndexer: SpellIndexer) extends SpellCorrector{
  val spellChecker = spellIndexer.getSpellChecker

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
    val scorer = new SuggestionScorer(spellIndexer.getTermStatsReader)
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
    if (spellChecker.exist(term) || similar.isEmpty) Array(term) // ++ similar.drop(1)
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

class SuggestionScorer(statsReader: TermStatsReader) {

  private var statsMap = Map.empty[String, SimpleTermStats]
  private var jointMap = Map.empty[(String, String), Int]

  def warmUpStatsMap(words: Set[String]) = words.foreach{getOrUpdateStats(_)}

  private def getOrUpdateStats(word: String) = statsMap.getOrElse( word,
    { val stat = statsReader.getSimpleTermStats(word); statsMap += (word -> stat); stat }
  )

  private def getOrUpdateJoint(key: (String, String)) = jointMap.getOrElse(key, {
      val (a, b) = key
      val (aStat, bStat) = (getOrUpdateStats(a), getOrUpdateStats(b))
      val inter = (aStat.docIds intersect bStat.docIds).size
      jointMap += key -> inter
      inter
    }
  )

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
        val pairScore = getOrUpdateJoint(key)
        score *= pairScore.max(1)     // smooth
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
