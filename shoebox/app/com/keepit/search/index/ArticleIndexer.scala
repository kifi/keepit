package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.UserToUserEdgeSet
import com.keepit.model._
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.Version
import java.io.File
import java.io.IOException

object ArticleIndexer {
  def apply(indexDirectory: Directory, articleStore: ArticleStore, uriGraph: URIGraph): ArticleIndexer = {
    val analyzer = new StandardAnalyzer(Version.LUCENE_36)
    analyzer.setMaxTokenLength(256)
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore, uriGraph)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: ArticleStore, uriGraph: URIGraph)
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
  
  private val parser = new ArticleQueryParser

  def parse(queryText: String): Query = {
    parser.parse(queryText)
  }
  
  def search(queryString: String): Seq[Hit] = searcher.search(parse(queryString))
  
  def search(queryString: String, userId: Id[User], friends: Set[Id[User]],
             maxMyBookMark: Int, maxFriendsBookmark: Int, maxOthersBookmark: Int): ArticleSearchResult = {
    
    // get searchers. subsequent operations should use these for consistency since indexing may refresh them
    val articleSearcher = searcher
    val graphSearcher = uriGraph.getURIGraphSearcher

    val myUris = graphSearcher.getUserToUriEdgeSet(userId).destIdLongSet
    val friendlyUris = myUris ++ friends.flatMap(graphSearcher.getUserToUriEdgeSet(_).destIdLongSet)
    val friendEdgeSet = new UserToUserEdgeSet(userId, friends)

    val mapper = articleSearcher.idMapper
    val myHits = articleSearcher.getHitQueue(maxMyBookMark)
    val friendsHits = articleSearcher.getHitQueue(maxFriendsBookmark)
    val othersHits = articleSearcher.getHitQueue(maxOthersBookmark)
    
    articleSearcher.doSearch(parse(queryString)){ scorer =>
      var doc = scorer.nextDoc()
      var score = scorer.score()
      while (doc != NO_MORE_DOCS) {
        val id = mapper.getId(doc)
        if (friendlyUris.contains(id)) {
          if (myUris.contains(id)) {
            myHits.insert(id, score)
          } else {
            friendsHits.insert(id, score)
          }
        } else {
          othersHits.insert(id, score)
        }
        doc = scorer.nextDoc()
      }
    }
    
    ArticleSearchResult(
      myHits.toList.map{ h =>
        val id = Id[NormalizedURI](h.id)
        ArticleHit(id, graphSearcher.intersect(friendEdgeSet, graphSearcher.getUriToUserEdgeSet(id)).destIdSet, h.score)
      }.toSeq,
      friendsHits.toList.map{ h =>
        val id = Id[NormalizedURI](h.id)
        ArticleHit(id, graphSearcher.intersect(friendEdgeSet, graphSearcher.getUriToUserEdgeSet(id)).destIdSet, h.score)
      }.toSeq,
      othersHits.toList.map{ h =>
        val id = Id[NormalizedURI](h.id)
        ArticleHit(id, Set.empty[Id[User]], h.score)
      }.toSeq
    )
  }
  
  def buildIndexable(uri: NormalizedURI) = {
    new ArticleIndexable(uri.id.get, uri, articleStore)
  }
  
  class ArticleIndexable(override val id: Id[NormalizedURI], val uri: NormalizedURI, articleStore: ArticleStore) extends Indexable[NormalizedURI] {
    override def buildDocument = {
      val doc = super.buildDocument
      articleStore.get(uri.id.get) match {
        case Some(article) =>
          val title = buildTextField("t", article.title)
          val content = buildTextField("c", article.content)
          doc.add(title)
          doc.add(content)
          doc
        case None => doc
      }
    }
  }
  
  class ArticleQueryParser extends QueryParser(Version.LUCENE_36, "b", indexWriterConfig.getAnalyzer()) {
    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      (super.getFieldQuery("t", queryText, quoted), super.getFieldQuery("c", queryText, quoted)) match {
        case (null, null) => null
        case (query, null) => query
        case (null, query) => query
        case (q1, q2) =>
          val booleanQuery = new BooleanQuery
          booleanQuery.add(q1, Occur.SHOULD)
          booleanQuery.add(q2, Occur.SHOULD)
          booleanQuery
      }
    }
  }
}

case class ArticleSearchResult(myHits: Seq[ArticleHit], friendsHits: Seq[ArticleHit], othersHits: Seq[ArticleHit])

case class ArticleHit(uriId: Id[NormalizedURI], friends: Set[Id[User]], score: Float)
