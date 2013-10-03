package com.keepit.search

import com.keepit.search.phrasedetector.{PhraseDetector, NlpPhraseDetector}
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.PercentMatch
import com.keepit.search.query.parser.QueryExpansion
import com.keepit.search.query.parser.QueryParserException
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.SiteQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.query.MultiplicativeBoostQuery
import com.keepit.search.query.BoostQuery
import com.keepit.search.query.PhraseProximityQuery
import com.keepit.search.query.NamedQueryContext
import com.keepit.search.query.NamedQuery


class MainQueryParser(
  lang: Lang,
  analyzer: Analyzer,
  stemmingAnalyzer: Analyzer,
  proximityBoost: Float,
  semanticBoost: Float,
  phraseBoost: Float,
  override val siteBoost: Float,
  override val concatBoost: Float,
  phraseDetector: PhraseDetector
) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch with QueryExpansion {

  val namedQueryContext = new NamedQueryContext

  private def namedQuery(name: String, query: Query) = new NamedQuery(name, query, namedQueryContext)

  override def parse(queryText: CharSequence): Option[Query] = {
    super.parse(queryText).map{ query =>
      val numTextQueries = textQueries.size
      if (numTextQueries <= 0) query
      else {
        val phrases = if (numTextQueries > 1 && phraseBoost > 0.0f) {
          val p = phraseDetector.detectAll(phTerms)
          if (p.size > 0) p else NlpPhraseDetector.detectAll(queryText.toString, stemmingAnalyzer, lang)
        } else {
          Set.empty[(Int, Int)]
        }

        val auxQueries = ArrayBuffer.empty[Query]
        val auxStrengths = ArrayBuffer.empty[Float]

        if (semanticBoost > 0.0f) {
          val svq = SemanticVectorQuery(svTerms, fallbackField = "title_stemmed")
          auxQueries += namedQuery("semantic vector", svq)
          auxStrengths += semanticBoost
        }

        if (proximityBoost > 0.0f && numTextQueries > 1) {
          val proxQ = new DisjunctionMaxQuery(0.0f)
          proxQ.add(ProximityQuery(proxTermsFor("cs"), phrases, phraseBoost))
          proxQ.add(ProximityQuery(proxTermsFor("ts"), phrases, phraseBoost))
          proxQ.add(ProximityQuery(proxTermsFor("title_stemmed"), phrases, phraseBoost))
          auxQueries += namedQuery("proximity", proxQ)
          auxStrengths += proximityBoost
        }

        if (!auxQueries.isEmpty) {
          new MultiplicativeBoostQuery(query, auxQueries.toArray, auxStrengths.toArray)
        } else {
          query
        }
      }
    }
  }

  private[this] def phTerms: IndexedSeq[Term] = {
    textQueries.flatMap{ _.stems }
  }


  private[this] def proxTermsFor(field: String): Seq[Seq[Term]] = {
    textQueries.foldLeft(new ArrayBuffer[ArrayBuffer[Term]]){ (terms, q) =>
      val concatTerms = q.concatStems.map{ new Term(field, _) }
      q.stems.foreach{ t =>
        val buf = ArrayBuffer(new Term(field, t.text))
        buf ++= concatTerms
        terms += buf
      }
      terms
    }
  }

  private[this] def svTerms: Seq[Term] = {
    textQueries.flatMap{ _.stems.map{ t => new Term("sv", t.text) } }
  }
}
