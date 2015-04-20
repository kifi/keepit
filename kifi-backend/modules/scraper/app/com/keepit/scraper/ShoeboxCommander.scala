package com.keepit.scraper

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.URI
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.shoebox.{ ShoeboxScraperClient, ShoeboxServiceClient }
import scala.concurrent.{ Future, Await, Awaitable }
import com.keepit.model._
import com.keepit.common.db.{ State, Id }
import scala.concurrent.duration._
import com.keepit.common.concurrent.{ ReactiveLock, ExecutionContext }
import java.util.concurrent.locks.ReentrantLock
import com.keepit.common.logging.Logging

@Singleton
class ShoeboxCommander @Inject() (
  config: ScraperConfig,
  shoeboxServiceClient: ShoeboxServiceClient,
  shoeboxScraperClient: ShoeboxScraperClient)
    extends Logging {

  implicit val serviceCallTimeout = config.serviceCallTimeout
  implicit val fjCtx = ExecutionContext.fj

  val uriUpdateLock = new ReactiveLock(4, Some(100))
  val saveScrapeLock = new ReactiveLock(2, Some(100))

  def assignTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = shoeboxScraperClient.assignScrapeTasks(zkId, max)

  def getNormalizedUri(uri: NormalizedURI): Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = uriUpdateLock.withLockFuture(shoeboxScraperClient.updateNormalizedURIState(uriId, state))
  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = saveScrapeLock.withLockFuture(shoeboxScraperClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE)))
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = uriUpdateLock.withLockFuture(shoeboxScraperClient.recordPermanentRedirect(uri, redirect))
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    uriUpdateLock.withLockFuture(shoeboxScraperClient.recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls))
  }

  def updateURIRestriction(uriId: Id[NormalizedURI], r: Option[Restriction]): Future[Unit] = uriUpdateLock.withLockFuture(shoeboxServiceClient.updateURIRestriction(uriId, r))
}
