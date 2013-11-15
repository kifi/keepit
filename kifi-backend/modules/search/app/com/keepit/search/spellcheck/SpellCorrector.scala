package com.keepit.search.spellcheck

import com.google.inject.{ImplementedBy, Inject, Singleton}

@ImplementedBy(classOf[SpellCorrectorImpl])
trait SpellCorrector {
  def getAlternativeQuery(input: String): String
}

@Singleton
class SpellCorrectorImpl @Inject()(spellIndexer: SpellIndexer) extends SpellCorrector{
  val spellChecker = spellIndexer.getSpellChecker

  def getAlternativeQuery(queryText: String) = {
    val terms = queryText.split(" ")
    terms.map(t => {
      if (spellChecker.exist(t)) t
      else {
        val suggest = getSimilarTerm(t)
        if (suggest.size == 0) t else suggest(0)
      }
    }).toList.mkString(" ")
  }

  // TODO: return more suggestions. Choose best suggestion based on surrounding text
  def getSimilarTerm(termText: String) = {
    spellChecker.suggestSimilar(termText, 1)
  }
}
