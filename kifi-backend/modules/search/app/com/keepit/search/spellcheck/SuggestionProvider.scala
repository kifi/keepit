package com.keepit.search.spellcheck

// e.g. List(Array("math", "myth", "maths"), Array("lean", "learn", "leap") )
case class SpellVariations(variations: List[Array[String]]) {
  override def toString(): String = variations.map { _.mkString(" ") }.mkString("\n")
}

trait SuggestionProvider {
  def makeSuggestions(input: String, spellVariations: SpellVariations): Array[ScoredSuggest] // ranked
}

class SlowSuggestionProvider(termScorer: TermScorer) extends SuggestionProvider {

  override def makeSuggestions(input: String, spellVariations: SpellVariations): Array[ScoredSuggest] = {
    val unscored = unscoredSuggestions(spellVariations)
    rank(input, unscored)
  }

  // exponential
  private def getPaths(variations: List[Array[String]]): Array[Array[String]] = {
    variations match {
      case head :: tail => {
        val paths = getPaths(tail)
        if (paths.isEmpty) head.map { x => Array(x) }
        else for { x <- head; path <- paths } yield { x +: path }
      }
      case Nil => Array()
    }
  }

  private def unscoredSuggestions(spellVariations: SpellVariations): Array[Suggest] = {
    val paths = getPaths(spellVariations.variations)
    paths.map { path => path.mkString(" ") }.map { Suggest(_) }
  }

  private def score(suggest: Suggest): ScoredSuggest = {
    if (suggest.value.trim == "") return ScoredSuggest("", 0)
    val words = suggest.value.split(" ")
    if (words.size == 1) {
      val score = termScorer.scoreSingleTerm(words.head)
      ScoredSuggest(suggest.value, score)
    } else {
      val pairScore = words.sliding(2, 1).map { case Array(a, b) => termScorer.scorePairTerms(a, b) }.foldLeft(1f)(_ * _)
      val tripleScore = if (words.size < 3) 1f else {
        words.sliding(3, 1).map { case Array(a, b, c) => termScorer.scoreTripleTerms(a, b, c) }.foldLeft(1f)(_ * _)
      }
      ScoredSuggest(suggest.value, pairScore * tripleScore)
    }
  }

  private def rank(input: String, suggests: Array[Suggest]): Array[ScoredSuggest] = {
    suggests.map { score(_) }.sortBy(_.score * (-1.0))
  }

}

class ViterbiSuggestionProvider(termScorer: TermScorer) extends SuggestionProvider {

  override def makeSuggestions(input: String, spellVariations: SpellVariations): Array[ScoredSuggest] = {
    val variations = spellVariations.variations
    if (variations.size == 1) {
      val scored = variations.head.map(_.trim).map { term =>
        if (term == "") ScoredSuggest("", 0f)
        else ScoredSuggest(term, termScorer.scoreSingleTerm(term))
      }
      Array(scored.sortBy(_.score * (-1)).head)
    } else {
      val (trellis, transitionScores) = (makeTrellis(spellVariations), makeTransitionScores(spellVariations))
      val v = new Viterbi()
      val path = v.solve(trellis, transitionScores)
      Array(constructSuggestion(spellVariations, path))
    }
  }

  private def makeTrellis(v: SpellVariations) = {
    Trellis(v.variations.map { _.size }.toArray)
  }
  private def makeTransitionScores(v: SpellVariations) = {
    val variations = v.variations.toArray
    val sizes = variations.map { _.size }
    val scores = (0 until (variations.size - 1)).map { k =>
      val score = for { i <- 0 until sizes(k); j <- 0 until sizes(k + 1) } yield {
        val (a, b) = (variations(k)(i), variations(k + 1)(j))
        val score = termScorer.scorePairTerms(a, b)
        (i, j) -> score
      }
      TransitionScore(score.toMap)
    }
    TransitionScores(scores.toArray)
  }

  private def constructSuggestion(v: SpellVariations, p: Path): ScoredSuggest = {
    val variations = v.variations.toArray
    val indexes = p.path
    val suggest = (variations zip indexes).map { case (arr, idx) => arr(idx) }.mkString(" ")
    ScoredSuggest(suggest, p.score)
  }
}
