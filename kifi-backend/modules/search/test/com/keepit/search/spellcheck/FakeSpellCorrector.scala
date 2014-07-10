package com.keepit.search.spellcheck

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import org.apache.lucene.analysis.standard.StandardAnalyzer

class FakeSpellCorrector() extends SpellCorrector {
  def getScoredSuggestions(input: String, numSug: Int, enableBoost: Boolean): Array[ScoredSuggest] = Array()
}
