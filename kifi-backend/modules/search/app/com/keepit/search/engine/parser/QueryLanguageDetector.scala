package com.keepit.search.engine.parser

import com.keepit.search.{ Searcher, Lang }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.parser.{ TermIterator, QuerySpec, DefaultSyntax, QueryParser }
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import scala.collection.mutable.{ ListBuffer, Map => MutableMap }
import scala.math._

class QueryLanguageDetector(searcher: Searcher) {

  def detect(query: String, prior: Map[Lang, Double]): Map[Lang, Double] = {
    val querySpecs = new ListBuffer[QuerySpec]()

    val parser = new QueryParser(DefaultAnalyzer.defaultAnalyzer, DefaultAnalyzer.defaultAnalyzer) with DefaultSyntax {
      override protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query] = {
        querySpecs ++= querySpecList
        None
      }
    }
    parser.parse(query)

    val languages = prior.keySet
    val terms: MutableMap[Term, TermLangStats] = MutableMap()

    // extract terms
    languages.foreach { lang =>
      querySpecs.take(16).foreach { querySpec =>
        extractTerms("cs", querySpec.term, lang, terms)
      }
    }

    // get frequency of each term
    terms.foreach { case (term, stats) => stats.addFreq(searcher.freq(term)) }

    // probability
    val alpha = 1.0 // smoothing
    val totalFreq = (terms.valuesIterator.map(_.getFreq()).sum).toDouble + (terms.size.toDouble * alpha)
    prior.iterator.map {
      case (lang, logPrior) =>
        val totalFreqInLang = (terms.valuesIterator.map(_.getFreq(lang)).sum.toDouble + (terms.size.toDouble * alpha))
        val logProb = terms.valuesIterator.foldLeft(logPrior) { (LogPrior, stats) =>
          log((stats.getFreq(lang).toDouble + alpha) / totalFreqInLang) - log((stats.getFreq().toDouble + alpha) / totalFreq) + logPrior
        }
        (lang, logProb)
    }.toMap
  }

  private def extractTerms(field: String, text: String, lang: Lang, terms: MutableMap[Term, TermLangStats]): Unit = {
    val it = new TermIterator(field, text, DefaultAnalyzer.getAnalyzerWithStemmer(lang))
    it.foreach { term =>
      terms.get(term) match {
        case Some(stat) => stat.addLang(lang)
        case None => terms += (term -> new TermLangStats(Set(lang)))
      }
    }
  }
}

final class TermLangStats(initLang: Set[Lang] = Set()) {
  private[this] var freq: Int = 0
  private[this] var langs: Set[Lang] = initLang

  def addFreq(n: Int): Unit = { freq += n }
  def addLang(lang: Lang): Unit = { langs += lang }

  @inline
  def getFreq(): Int = freq

  @inline
  def getFreq(lang: Lang): Int = { if (langs.contains(lang)) freq else 0 }
}
