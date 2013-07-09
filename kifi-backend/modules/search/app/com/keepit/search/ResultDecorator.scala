package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search.index.Analyzer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryUtil
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import scala.concurrent.duration._
import java.io.StringReader
import scala.collection.immutable.SortedMap
import com.keepit.search.index.SymbolDecompounder
import com.keepit.common.logging.Logging
import com.keepit.social.BasicUser

trait ResultDecorator {
  def decorate(resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]]
}

object ResultDecorator extends Logging {

  private[this] val emptyMatches = Seq.empty[(Int, Int)]

  def apply(searcher: MainSearcher, shoeboxClient: ShoeboxServiceClient, searchConfig: SearchConfig): ResultDecorator = {
    val decorateResultWithCollections = searchConfig.asBoolean("decorateResultWithCollections")
    new ResultDecoratorImpl(searcher, shoeboxClient, decorateResultWithCollections)
  }

  def highlight(text: String, analyzer: Analyzer, field: String, terms: Option[Set[String]]): Seq[(Int, Int)] = {
    terms match {
      case Some(terms) => highlight(text, analyzer, field, terms)
      case _ =>
        log.error("no term specified")
        emptyMatches
    }
  }

  def highlight(text: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
    var positions: SortedMap[Int, Int] = SortedMap.empty[Int, Int]
    val ts = analyzer.tokenStream(field, new StringReader(text))
    if (ts.hasAttribute(classOf[OffsetAttribute]) && ts.hasAttribute(classOf[CharTermAttribute])) {
      val termAttr = ts.getAttribute(classOf[CharTermAttribute])
      val offsetAttr = ts.getAttribute(classOf[OffsetAttribute])
      ts.reset()
      while (ts.incrementToken()) {
        val termString = new String(termAttr.buffer(), 0, termAttr.length())
        if (terms.contains(termString)) {
          val thisStart = offsetAttr.startOffset()
          val thisEnd = offsetAttr.endOffset()
          positions.get(thisStart) match {
            case Some(endOffset) =>
              if (endOffset < thisEnd) positions += (thisStart -> thisEnd)
            case _ => positions += (thisStart -> thisEnd)
          }
        }
      }
      var curStart = -1
      var curEnd = -1
      positions.foreach{ case (start, end) =>
        if (start < curEnd) { // overlapping
          if (curEnd < end) {
            positions += (curStart -> end) // extend the highlight region
            positions -= start
            curEnd = end
          } else { // inclusion. remove it
            positions -= start
          }
        } else {
          curStart = start
          curEnd = end
        }
      }
      if (positions.nonEmpty) positions.toSeq else emptyMatches
    } else {
      if (ts.hasAttribute(classOf[OffsetAttribute])) log.error("offset attribute not found")
      if (ts.hasAttribute(classOf[CharTermAttribute])) log.error("char term attribute not found")
      emptyMatches
    }
  }

  def highlightURL(url: String, analyzer: Analyzer, field: String, terms: Option[Set[String]]): Seq[(Int, Int)] = {
    terms match {
      case Some(terms) => highlightURL(url, analyzer, field, terms)
      case _ =>
        log.error("no term specified")
        emptyMatches
    }
  }

  private[this] val urlSpecialCharRegex = """[/\.:#&+~]""".r

  def highlightURL(url: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
    val text = urlSpecialCharRegex.replaceAllIn(url, " ")
    highlight(text, analyzer, field, terms)
  }
}

class ResultDecoratorImpl(searcher: MainSearcher, shoeboxClient: ShoeboxServiceClient, decorateResultWithCollections: Boolean) extends ResultDecorator {

  override def decorate(resultSet: ArticleSearchResult): Future[Seq[PersonalSearchResult]] = {
    val collectionSearcher = searcher.collectionSearcher
    val myCollectionEdgeSet = collectionSearcher.myCollectionEdgeSet
    val hits = resultSet.hits
    val users = hits.map(_.users).flatten.distinct
    val usersFuture = shoeboxClient.getBasicUsers(users)

    val field = "title_stemmed"
    val analyzer = DefaultAnalyzer.forIndexingWithStemmer(searcher.getLang)
    val terms = searcher.getParsedQuery.map(QueryUtil.getTerms(field, _).map(_.text()))

    val personalSearchHits = hits.map{ h =>
      if (h.isMyBookmark) {
        val r = searcher.getBookmarkRecord(h.uriId).getOrElse(throw new Exception(s"missing bookmark record: uri id = ${h.uriId}"))

        val collections = if (decorateResultWithCollections) {
          val collIds = collectionSearcher.intersect(myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(h.uriId)).destIdLongSet
          if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map{ id => collectionSearcher.getExternalId(id) })
        } else None

        PersonalSearchHit(
          r.uriId,
          Some(r.title),
          r.url,
          r.isPrivate,
          ResultDecorator.highlight(r.title, analyzer, field, terms),
          ResultDecorator.highlightURL(r.url, analyzer, field, terms),
          collections
        )
      } else {
        val r = searcher.getArticleRecord(h.uriId).getOrElse(throw new Exception(s"missing article record: uri id = ${h.uriId}"))
        PersonalSearchHit(
          r.id,
          Some(r.title),
          r.url,
          false,
          ResultDecorator.highlight(r.title, analyzer, field, terms),
          ResultDecorator.highlightURL(r.url, analyzer, field, terms),
          None
        )
      }
    }

    usersFuture.map{ basicUserMap =>
      (hits, resultSet.scorings, personalSearchHits).zipped.toSeq.map { case (hit, score, personalHit) =>
        val users = hit.users.map(basicUserMap)
        val isNew = (!hit.isMyBookmark && score.recencyScore > 0.5f)
        PersonalSearchResult(
          personalHit,
          hit.bookmarkCount,
          hit.isMyBookmark,
          personalHit.isPrivate,
          users,
          hit.score,
          isNew)
      }
    }
  }
}
