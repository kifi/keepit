package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.service.RequestConsolidator
import com.keepit.search.phrasedetector.{PhraseDetector, NlpPhraseDetector}
import com.keepit.search.graph.CollectionSearcherWithUser
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.PercentMatch
import com.keepit.search.query.parser.QueryExpansion
import com.keepit.search.query.parser.QueryParserException
import com.keepit.search.query.ExistenceBoostQuery
import com.keepit.search.query.MultiplicativeBoostQuery
import com.keepit.search.query.NamedQueryContext
import com.keepit.search.query.NamedQuery
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.TextQuery
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
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MainQueryParser(
  lang: Lang,
  analyzer: Analyzer,
  stemmingAnalyzer: Analyzer,
  proximityBoost: Float,
  semanticBoost: Float,
  phraseBoost: Float,
  override val siteBoost: Float,
  override val concatBoost: Float,
  homePageBoost: Float,
  override val useSemanticMatch: Boolean,
  proximityGapPanelty: Float,
  proximityThreshold: Float,
  phraseDetector: PhraseDetector,
  phraseDetectionConsolidator: RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]],
  monitoredAwait: MonitoredAwait
) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch with QueryExpansion {

  var collectionIds = Set.empty[Long]

  var phraseDetectionTime: Long = 0L
  var nlpPhraseDetectionTime: Long = 0L

  override def parse(queryText: CharSequence): Option[Query] = {
    throw new UnsupportedOperationException("use parse(queryText,collectionSearcher)")
  }

  def parse(queryText: CharSequence, collectionSearcher: Option[CollectionSearcherWithUser]): Option[Query] = {
    super.parse(queryText).map{ query =>
      val numTextQueries = textQueries.size
      if (numTextQueries <= 0) query
      else if (numTextQueries > ProximityQuery.maxLength) query // too many terms, skip proximity and semantic vector
      else {
        val phrasesFuture = if (numTextQueries > 1 && phraseBoost > 0.0f) detectPhrases(queryText, lang) else null

        // detect collection names and augment TextQueries
        collectionSearcher.foreach{ cs =>
          val indexToTextQuery: IndexedSeq[TextQuery] = textQueries.flatMap{ t => t.stems.map{ s => t } }
          cs.detectCollectionNames(phStemmedTerms).foreach{ case (index, length, collectionId) =>
            collectionIds += collectionId
            var i = index
            val end = index + length
            while (i < end) {
              indexToTextQuery(i).addCollectionQuery(collectionId, 1.5f)
              i += 1
            }
          }
        }

        if (semanticBoost > 0.0f) {
          textQueries.foreach{ textQuery =>
            textQuery.setSemanticBoost(semanticBoost)
            textQuery.stems.map{ stemTerm => textQuery.addSemanticVectorQuery("sv", stemTerm.text) }
          }
        }

        if (proximityBoost > 0.0f && numTextQueries > 1) {
          val phrases = if (phrasesFuture != null ) monitoredAwait.result(phrasesFuture, 3 seconds, "phrase detection") else Set.empty[(Int, Int)]
          val proxQ = new DisjunctionMaxQuery(0.0f)
          proxQ.add(ProximityQuery(proxTermsFor("cs"), phrases, phraseBoost, proximityGapPanelty, proximityThreshold))
          proxQ.add(ProximityQuery(proxTermsFor("ts"), phrases, phraseBoost, proximityGapPanelty, proximityThreshold))
          proxQ.add(ProximityQuery(proxTermsFor("title_stemmed"), phrases, phraseBoost, proximityGapPanelty, proximityThreshold))
          new MultiplicativeBoostQuery(query, proxQ, proximityBoost)
        } else if (numTextQueries == 1 && phTerms.nonEmpty && homePageBoost > 0.0f) {
          val homePageQuery = if (phTerms.size == 1) {
            new TermQuery(new Term("home_page", phTerms(0).text))
          } else {
            val hpQ = new PhraseQuery()
            phTerms.foreach{ t => hpQ.add(new Term("home_page", t.text)) }
            hpQ
          }
          new ExistenceBoostQuery(query, homePageQuery, homePageBoost)
        } else {
          query
        }
      }
    }
  }

  private[this] lazy val phTerms: IndexedSeq[Term] = {
    textQueries.flatMap{ _.terms }
  }
  private[this] lazy val phStemmedTerms: IndexedSeq[Term] = {
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

  def svTerms: Seq[Term] = {
    textQueries.flatMap{ _.stems.map{ t => new Term("sv", t.text) } }
  }

  private def detectPhrases(queryText: CharSequence, lang: Lang): Future[Set[(Int, Int)]] = {
    phraseDetectionConsolidator((queryText, lang)){ _ =>
      SafeFuture {
        val tPhraseDetection = System.currentTimeMillis
        val p = phraseDetector.detectAll(phStemmedTerms)
        phraseDetectionTime = System.currentTimeMillis - tPhraseDetection

        if (p.size > 0) p else {
          val tNlpPhraseDetection = System.currentTimeMillis
          val nlpPhrases = NlpPhraseDetector.detectAll(queryText.toString, stemmingAnalyzer, lang)
          nlpPhraseDetectionTime = System.currentTimeMillis - tNlpPhraseDetection
          nlpPhrases
        }
      }
    }
  }
}
