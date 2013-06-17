package com.keepit.search.query

class NamedQueryContext {
  private[this] var accessorMap = Map.empty[String, NamedQueryScoreAccessor]

  def getScoreAccessor(name: String): NamedQueryScoreAccessor = {
    accessorMap.get(name) match {
      case Some(acc) => acc
      case None =>
        val acc = new NamedQueryScoreAccessor
        accessorMap += (name -> acc)
        acc
    }
  }

  def setScorer(name: String, namedScorer: NamedScorer) { getScoreAccessor(name).setScorer(namedScorer) }

  def reset() { accessorMap.values.foreach(_.reset()) }
}

class NamedQueryScoreAccessor {
  private[this] var namedScorer: NamedScorer = null

  def setScorer(scorer: NamedScorer) { namedScorer = scorer }

  def getScore(doc: Int) = if (namedScorer != null) namedScorer.getScore(doc) else 0.0f

  def reset() { namedScorer = null }
}
