package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.SearchConfig
import com.keepit.model._
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.Version
import java.io.File
import java.io.IOException
import java.io.StringReader
import scala.math._
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.BooleanQueryWithPercentMatch

object ArticleIndexer {

  def apply(indexDirectory: Directory, articleStore: ArticleStore): ArticleIndexer = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: ArticleStore)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100
  val fetchSize = commitBatchSize * 3

  def run(): Int = {
    log.info("starting a new indexing round")
    try {
      val uris = CX.withConnection { implicit c =>
        val uris = NormalizedURI.getByState(SCRAPE_FAILED, fetchSize)
        if (uris.size < fetchSize) uris ++ NormalizedURI.getByState(SCRAPED, fetchSize - uris.size)
        else uris
      }
      var cnt = 0
      indexDocuments(uris.iterator.map{ uri => buildIndexable(uri) }, commitBatchSize){ commitBatch =>
        commitBatch.foreach{ case (indexable, indexError)  =>
          CX.withConnection { implicit c =>
            val articleIndexable = indexable.asInstanceOf[ArticleIndexable]
            val state = indexError match {
              case Some(error) =>
                findNextState(articleIndexable.uri.state -> Set(INDEX_FAILED, FALLBACK_FAILED))
              case None =>
                cnt += 1
                findNextState(articleIndexable.uri.state -> Set(INDEXED, FALLBACKED))
            }
            NormalizedURI.get(indexable.id).withState(state).save
          }
        }
      }
      cnt
    } catch {
      case ex: Throwable =>
        log.error("error in indexing run", ex)
        throw ex
    }
  }

  def getQueryParser(lang: Lang): QueryParser = getQueryParser(lang, 0.0f, 0.0f)

  def getQueryParser(lang: Lang, proximityBoost: Float, semanticBoost: Float): QueryParser = {
    val total = 1.0f + proximityBoost + semanticBoost
    val parser = new ArticleQueryParser(DefaultAnalyzer.forParsing(lang), 1.0f/total, proximityBoost/total, semanticBoost/total)
    DefaultAnalyzer.forParsingWithStemmer(lang).foreach{ parser.setStemmingAnalyzer(_) }
    parser
  }

  def getArticleSearcher() = searcher

  def getPersonalizedArticleSearcher(uris: Set[Id[NormalizedURI]]) = PersonalizedSearcher(searcher, uris.map(id => id.id))

  def search(queryText: String): Seq[Hit] = {
    parseQuery(queryText) match {
      case Some(query) => searcher.search(query)
      case None => Seq.empty[Hit]
    }
  }

  def buildIndexable(id: Id[NormalizedURI]) = {
    val uri = CX.withConnection{ implicit c => NormalizedURI.get(id) }
    buildIndexable(uri)
  }

  def buildIndexable(uri: NormalizedURI) = {
    new ArticleIndexable(uri.id.get, uri, articleStore)
  }

  class ArticleIndexable(override val id: Id[NormalizedURI], val uri: NormalizedURI, articleStore: ArticleStore) extends Indexable[NormalizedURI] {
    implicit def toReader(text: String) = new StringReader(text)

    override def buildDocument = {
      val doc = super.buildDocument
      articleStore.get(uri.id.get) match {
        case Some(article) =>
          val titleLang = article.titleLang.getOrElse(Lang("en"))
          val contentLang = article.contentLang.getOrElse(Lang("en"))
          doc.add(buildKeywordField("cl", contentLang.lang))
          doc.add(buildKeywordField("tl", titleLang.lang))

          val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
          val contentAnalyzer = DefaultAnalyzer.forIndexing(contentLang)
          val title = buildTextField("t", article.title, titleAnalyzer)
          val content = buildTextField("c", article.content, contentAnalyzer)
          doc.add(title)
          doc.add(content)

          DefaultAnalyzer.forIndexingWithStemmer(titleLang).foreach{ analyzer =>
            doc.add(buildTextField("ts", article.title, analyzer))
          }
          DefaultAnalyzer.forIndexingWithStemmer(contentLang).foreach{ analyzer =>
            doc.add(buildTextField("cs", article.content, analyzer))
          }
          doc.add(buildSemanticVectorField("sv", titleAnalyzer.tokenStream("t", article.title), contentAnalyzer.tokenStream("c", article.content)))

          // index domain name
          URI.parse(uri.url) match {
            case Some(uri) =>
              uri.host match {
                case Some(Host(domain @ _*)) =>
                  doc.add(buildIteratorField("site", (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
                case _ =>
              }
            case _ =>
          }

          doc
        case None => doc
      }
    }
  }

  class ArticleQueryParser(analyzer: Analyzer, baseBoost: Float, proximityBoost: Float, semanticBoost: Float) extends QueryParser(analyzer) {

    super.setAutoGeneratePhraseQueries(true)

    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      field.toLowerCase match {
        case "site" => getSiteQuery(queryText)
        case _ => getTextQuery(queryText, quoted)
      }
    }

    private def getTextQuery(queryText: String, quoted: Boolean) = {
      val booleanQuery = new BooleanQuery(true)

      var query = super.getFieldQuery("t", queryText, quoted)
      if (query != null) booleanQuery.add(query, Occur.SHOULD)

      query = super.getFieldQuery("c", queryText, quoted)
      if (query != null) booleanQuery.add(query, Occur.SHOULD)

      if(!quoted) {
        super.getStemmedFieldQueryOpt("ts", queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
        super.getStemmedFieldQueryOpt("cs", queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
      }

      val clauses = booleanQuery.clauses
      if (clauses.size == 0) null
      else if (clauses.size == 1) clauses.get(0).getQuery()
      else booleanQuery
    }

    override def parseQuery(queryText: String) = {
      super.parseQuery(queryText).map{ query =>
        val terms = QueryUtil.getTerms(query)
        if (terms.size <= 0) query
        else {
          val booleanQuery = new BooleanQuery(true)
          query.setBoost(baseBoost)
          booleanQuery.add(query, Occur.MUST)
          val svq = SemanticVectorQuery("sv", terms)
          svq.setBoost(semanticBoost)
          booleanQuery.add(svq, Occur.SHOULD)
          val csterms = QueryUtil.getTermSeq("cs", query)
          val tsterms = QueryUtil.getTermSeq("ts", query)
          val cstermSize = csterms.size
          val tstermSize = tsterms.size
          if (cstermSize > 1 && tstermSize > 1) {
            val proxQ = new BooleanQuery(true)
            proxQ.add(ProximityQuery(csterms), Occur.SHOULD)
            proxQ.add(ProximityQuery(tsterms), Occur.SHOULD)
            proxQ.setBoost(proximityBoost)
            booleanQuery.add(proxQ, Occur.SHOULD)
          } else {
            if (cstermSize > 1 || tstermSize > 1) {
              val proxQ = ProximityQuery(if (cstermSize > 1) csterms else tsterms)
              proxQ.setBoost(proximityBoost)
              booleanQuery.add(proxQ, Occur.SHOULD)
            }
          }
          booleanQuery
        }
      }
    }
  }
}
