package com.keepit.search

import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.tracker._
import com.keepit.heimdal._
import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User}
import org.joda.time.DateTime
import com.keepit.common.net.{Host, URI}
import com.keepit.common.KestrelCombinator
import com.keepit.common.logging.Logging
import com.keepit.search.tracker.ClickedURI
import play.api.libs.concurrent.Execution.Implicits._

class SearchEventCommander @Inject() (
  shoeboxClient: ShoeboxServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  searchAnalytics: SearchAnalytics) extends Logging {

  def searched(userId: Id[User], searchedAt: DateTime, searchContext: BasicSearchContext, endedWith: String)(implicit context: HeimdalContext) = {
    searchAnalytics.searched(userId, searchedAt, searchContext, endedWith, context)
  }

  def clickedKifiResult(userId: Id[User], searchContext: BasicSearchContext, query: String, searchResultUrl: String, resultPosition: Int, isDemo: Boolean, clickedAt: DateTime, kifiHitContext: KifiHitContext)(implicit context: HeimdalContext) = {
    shoeboxClient.getNormalizedURIByURL(searchResultUrl).onSuccess { case Some(uri) =>
      val uriId = uri.id.get
      resultClickedTracker.add(userId, query, uriId, resultPosition, kifiHitContext.isOwnKeep, isDemo)
      clickHistoryTracker.add(userId, ClickedURI(uriId))
      if (kifiHitContext.isOwnKeep) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, kifiHitContext.keepers: _*)
    }
    searchAnalytics.clickedSearchResult(userId, clickedAt, searchContext, SearchEngine.Kifi, resultPosition, Some(kifiHitContext), context)
  }

  def clickedOtherResult(userId: Id[User], searchContext: BasicSearchContext, query: String, searchResultUrl: String, resultPosition: Int, clickedAt: DateTime, searchEngine: SearchEngine)(implicit context: HeimdalContext) = {
    getDestinationUrl(searchResultUrl, searchEngine).foreach { url =>
      shoeboxClient.getNormalizedURIByURL(url).onSuccess {
        case Some(uri) =>
          val uriId = uri.id.get
          resultClickedTracker.add(userId, query, uriId, resultPosition, false) // We do this for a Google result, too.
          clickHistoryTracker.add(userId, ClickedURI(uri.id.get))
        case None =>
          resultClickedTracker.moderate(userId, query)
      }
    }
    searchAnalytics.clickedSearchResult(userId, clickedAt, searchContext, searchEngine, resultPosition, None, context)
  }

  private def getDestinationUrl(searchResultUrl: String, searchEngine: SearchEngine): Option[String] = {
    searchEngine match {
      case SearchEngine.Google => searchResultUrl match {
        case URI(_, _, Some(Host("com", "youtube", _*)), _, _, _, _) => Some(searchResultUrl)
        case URI(_, _, Some(Host("org", "wikipedia", _*)), _, _, _, _) => Some(searchResultUrl)
        case URI(_, _, Some(host), _, Some("/url"), Some(query), _) if host.domain.contains("google") => query.params.find(_.name == "url").flatMap { _.decodedValue }
        case _ => None
      }
      case _ => None
    }
  } tap { urlOpt => if (urlOpt.isEmpty) log.error(s"failed to extract the destination URL from $searchEngine: $searchResultUrl") }

  def browsedPages(userId: Id[User], browsedUrls: Seq[String]): Unit = browsedUrls.foreach { url =>
    shoeboxClient.getNormalizedURIByURL(url).foreach(_.foreach { uri =>
      browsingHistoryTracker.add(userId, BrowsedURI(uri.id.get))
    })
  }

  def browsedUris(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Unit = {
    uriIds.foreach { uriId => browsingHistoryTracker.add(userId, BrowsedURI(uriId)) }
  }

}
