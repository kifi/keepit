package com.keepit.search.spellcheck

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import org.apache.lucene.analysis.standard.StandardAnalyzer

@ImplementedBy(classOf[SpellCorrectorImpl])
trait SpellCorrector {
  def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest]
}

class SpellCorrectorImpl(spellIndexer: SpellIndexer, suggestionProviderFlag: String, enableAdjScore: Boolean, orderedAdjScore: Boolean = false) extends SpellCorrector {
  val spellChecker = spellIndexer.getSpellChecker
  val stopwords = StandardAnalyzer.STOP_WORDS_SET
  def suggestionProvider = {
    val termScorer = new TermScorer(spellIndexer.getTermStatsReader, enableAdjScore, orderedAdjScore)
    suggestionProviderFlag match {
      case "viterbi" => new ViterbiSuggestionProvider(termScorer)
      case "slow" => new SlowSuggestionProvider(termScorer)
      case _ => new SlowSuggestionProvider(termScorer)
    }
  }

  override def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest] = {
    val variations = makeVariations(input, numSug)
    val suggests = suggestionProvider.makeSuggestions(input, variations)
    if (enableBoost) boost(input, suggests) else suggests
  }

  private def boost(input: String, suggests: Array[ScoredSuggest]): Array[ScoredSuggest] = {
    val deco = new ScoreDecorator(input)
    suggests.map { deco.decorate(_) }.sortBy(_.score * (-1.0))
  }

  private def makeVariations(input: String, numSug: Int): SpellVariations = {
    val terms = input.trim().split(" ")
    val variations = terms.map { getSimilarTerms(_, numSug.min(10)) }.toList // need a limit here
    SpellVariations(variations)
  }

  private def getSimilarTerms(term: String, numSug: Int): Array[String] = {
    val similar = spellChecker.suggestSimilar(term, numSug) // this never includes the original term
    if (spellChecker.exist(term) || stopwords.contains(term) || similar.isEmpty) Array(term) ++ similar.take(3) // add 3 just in case misspelling words were indexed
    else similar
  }
}

class MetaphoneBooster {
  val mdist = new MetaphoneDistance()
  def similarity(a: Array[String], b: Array[String]): Float = {
    (a zip b).map { case (x, y) => mdist.getDistance(x, y) }.foldLeft(1f)(_ * _)
  }
}

class CompositeBooster {
  val comp = new CompositeDistance()
  def similarity(a: Array[String], b: Array[String]): Float = {
    (a zip b).map { case (x, y) => comp.getDistance(x, y) }.foldLeft(1f)(_ * _)
  }
}

class ScoreDecorator(originalQuery: String) {
  val booster = new CompositeBooster
  val originalTerms = originalQuery.split(" ")
  def decorate(scoredSuggest: ScoredSuggest): ScoredSuggest = {
    val boost = booster.similarity(originalTerms, scoredSuggest.value.split(" "))
    ScoredSuggest(value = scoredSuggest.value, score = boost * scoredSuggest.score)
  }
}
