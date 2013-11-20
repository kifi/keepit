package com.keepit.search.spellcheck

// e.g. List(Array("math", "myth", "maths"), Array("lean", "learn", "leap") )
case class SpellVariations(variations: List[Array[String]])

trait SuggestionProvider {
  def makeSuggestions(input: String, spellVariations: SpellVariations): Array[ScoredSuggest]  // ranked
}

class SlowSuggestionProvider(termScorer: TermScorer) extends SuggestionProvider {

  // exponential
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

  private def unscoredSuggestions(spellVariations: SpellVariations): Array[Suggest] = {
    val paths = getPaths(spellVariations.variations)
    paths.map{path => path.mkString(" ")}.map{Suggest(_)}
  }

  private def score(suggest: Suggest): ScoredSuggest = {
    if (suggest.value.trim == "") return ScoredSuggest("", 0)
    val words = suggest.value.split(" ")
    if (words.size == 1) {
      val score = termScorer.scoreSingleTerm(words.head)
      ScoredSuggest(suggest.value, score)
    } else {
      val pairs = words.sliding(2, 1)
      val score = pairs.map{ case Array(a, b) => termScorer.scorePairTerms(a, b) }.foldLeft(1f)(_*_)
      ScoredSuggest(suggest.value, score)
    }
  }

  private def rank(input: String, suggests: Array[Suggest], enableBoost: Boolean = true): Array[ScoredSuggest] = {
    suggests.map{score(_)}.sortBy(_.score*(-1.0))
  }

  override def makeSuggestions(input: String, spellVariations: SpellVariations): Array[ScoredSuggest] = {
    val unscored = unscoredSuggestions(spellVariations)
    rank(input, unscored)
  }
}
