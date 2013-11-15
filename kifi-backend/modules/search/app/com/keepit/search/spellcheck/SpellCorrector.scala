package com.keepit.search.spellcheck

import com.google.inject.{ImplementedBy, Inject, Singleton}

@ImplementedBy(classOf[SpellCorrectorImpl])
trait SpellCorrector {
  def getSuggestion(input: String): String
  def getSuggestions(input: String, numSug: Int): Array[String]
}

@Singleton
class SpellCorrectorImpl @Inject()(spellIndexer: SpellIndexer) extends SpellCorrector{
  val spellChecker = spellIndexer.getSpellChecker

  override def getSuggestion(queryText: String): String = {
    val terms = queryText.trim().split(" ")
    terms.map{t => getSimilarTerms(t, 1).head }.mkString(" ")
  }

  override def getSuggestions(queryText: String, numSug: Int): Array[String] = {
    val terms = queryText.trim().split(" ")
    val variations = terms.map{ getSimilarTerms(_, numSug)}.toList
    val paths = getPaths(variations)
    paths.map{path => path.mkString(" ")}
  }

  // exponential. Use Viterbi-like algorithm later.
  def getPaths(variations: List[Array[String]]): Array[Array[String]] = {
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
    if (spellChecker.exist(term) || similar.isEmpty) Array(term)  // ++ similar.drop(1)
    else similar
  }
}
