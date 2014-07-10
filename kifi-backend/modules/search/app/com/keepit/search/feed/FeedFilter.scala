package com.keepit.search.feed

import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.NormalizedURIStates

case class FeedMetaInfo(
  uri: NormalizedURI,
  isSensitive: Boolean)

class FeedMetaInfoProvider @Inject() (
    shoeboxClient: ShoeboxServiceClient) {
  def getFeedMetaInfo(uriId: Id[NormalizedURI]): FeedMetaInfo = {
    val uri = Await.result(shoeboxClient.getNormalizedURI(uriId), 5 seconds)
    val sensitive = Await.result(shoeboxClient.isSensitiveURI(uri.url), 5 seconds)
    FeedMetaInfo(uri, sensitive)
  }
}

trait FeedFilter {
  def accept(meta: FeedMetaInfo): Boolean
}

class BasicFeedFilter extends FeedFilter {
  import com.keepit.model.NormalizedURIStates._
  val badScrapeStates = Set(SCRAPE_FAILED, UNSCRAPABLE)
  def accept(meta: FeedMetaInfo): Boolean = {
    meta.uri.redirect.isEmpty && !badScrapeStates.contains(meta.uri.state) && !meta.isSensitive
  }
}

class CompositeFeedFilter(filters: FeedFilter*) extends FeedFilter {
  def accept(meta: FeedMetaInfo): Boolean = filters.forall(_.accept(meta))
}
