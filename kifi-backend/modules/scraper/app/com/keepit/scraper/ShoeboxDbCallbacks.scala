package com.keepit.scraper

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.URI
import com.keepit.shoebox.{ ShoeboxScraperClient, ShoeboxServiceClient }
import scala.concurrent.{ Future, Await, Awaitable }
import com.keepit.model._
import com.keepit.common.db.{ State, Id }
import scala.concurrent.duration._
import com.keepit.common.concurrent.ExecutionContext
import java.util.concurrent.locks.ReentrantLock
import com.keepit.common.logging.Logging

@Singleton
class ShoeboxDbCallbackHelper @Inject() (
  config: ScraperConfig,
  shoeboxServiceClient: ShoeboxServiceClient,
  shoeboxScraperClient: ShoeboxScraperClient)
    extends SyncShoeboxDbCallbacks with ShoeboxDbCallbacks with Logging {

  implicit val serviceCallTimeout = config.serviceCallTimeout
  implicit val fjCtx = ExecutionContext.fj

  private val normalizedUriLock = new ReentrantLock()
  private val recordPermanentRedirectLock = new ReentrantLock()

  private def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, config.syncAwaitTimeout seconds)

  def syncSaveNormalizedUri(uri: NormalizedURI): NormalizedURI = {
    try {
      normalizedUriLock.lock()
      log.info(s"[${normalizedUriLock.getQueueLength}] about to persist $uri")
      val saved = await(saveNormalizedUri(uri))
      log.info(s"[${normalizedUriLock.getQueueLength}] done with persist $uri")
      saved
    } finally {
      normalizedUriLock.unlock()
    }
  }
  def syncUpdateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Unit = {
    try {
      normalizedUriLock.lock()
      log.info(s"[${normalizedUriLock.getQueueLength}] about to update $uriId")
      await(updateNormalizedURIState(uriId, state))
      log.info(s"[${normalizedUriLock.getQueueLength}] done with update $uriId")
    } finally {
      normalizedUriLock.unlock()
    }
  }
  def syncSaveScrapeInfo(info: ScrapeInfo): Unit = await(saveScrapeInfo(info))
  def syncSavePageInfo(info: PageInfo): Unit = await(savePageInfo(info))
  def syncSaveImageInfo(info: ImageInfo): Unit = await(saveImageInfo(info))
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Seq[Keep] = await(getBookmarksByUriWithoutTitle(uriId))
  def syncGetLatestKeep(url: String): Option[Keep] = await(getLatestKeep(url))
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = {
    try {
      recordPermanentRedirectLock.lock()
      log.info(s"[${recordPermanentRedirectLock.getQueueLength}] about to persist redirected $uri")
      val saved = await(recordPermanentRedirect(uri, redirect))
      log.info(s"[${recordPermanentRedirectLock.getQueueLength}] done with persist redirected $uri")
      saved
    } finally {
      recordPermanentRedirectLock.unlock()
    }
  }
  def syncSaveBookmark(bookmark: Keep): Keep = await(saveBookmark(bookmark))
  def syncRecordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Unit = {
    await(recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls))
  }

  def assignTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = shoeboxScraperClient.assignScrapeTasks(zkId, max)
  def getNormalizedUri(uri: NormalizedURI): Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }
  def saveNormalizedUri(uri: NormalizedURI): Future[NormalizedURI] = shoeboxScraperClient.saveNormalizedURI(uri)
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = shoeboxScraperClient.updateNormalizedURIState(uriId, state)
  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = shoeboxScraperClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE))
  def savePageInfo(info: PageInfo): Future[Unit] = shoeboxScraperClient.savePageInfo(info)
  def saveImageInfo(info: ImageInfo): Future[Unit] = shoeboxScraperClient.saveImageInfo(info)
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]] = shoeboxScraperClient.getBookmarksByUriWithoutTitle(uriId)
  def getLatestKeep(url: String): Future[Option[Keep]] = shoeboxScraperClient.getLatestKeep(url)
  def saveBookmark(bookmark: Keep): Future[Keep] = shoeboxScraperClient.saveBookmark(bookmark)
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxScraperClient.recordPermanentRedirect(uri, redirect)
  def isUnscrapableP(url: URI, destinationUrl: Option[String]) = shoeboxScraperClient.isUnscrapableP(url.toString(), destinationUrl)
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    shoeboxScraperClient.recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls)
  }
  override def updateURIRestriction(uriId: Id[NormalizedURI], r: Option[Restriction]): Unit = await({
    shoeboxServiceClient.updateURIRestriction(uriId, r)
  })
}

trait SyncShoeboxDbCallbacks {
  def syncSaveNormalizedUri(uri: NormalizedURI): NormalizedURI
  def syncUpdateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Unit
  def syncSaveScrapeInfo(info: ScrapeInfo): Unit
  def syncSavePageInfo(info: PageInfo): Unit
  def syncSaveImageInfo(info: ImageInfo): Unit
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Seq[Keep]
  def syncGetLatestKeep(url: String): Option[Keep]
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI
  def syncSaveBookmark(bookmark: Keep): Keep
  def syncRecordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Unit
  def updateURIRestriction(uriId: Id[NormalizedURI], r: Option[Restriction]): Unit
}

trait ShoeboxDbCallbacks {
  def assignTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]]
  def getNormalizedUri(uri: NormalizedURI): Future[Option[NormalizedURI]]
  def saveNormalizedUri(uri: NormalizedURI): Future[NormalizedURI]
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit]
  def saveScrapeInfo(info: ScrapeInfo): Future[Unit]
  def savePageInfo(info: PageInfo): Future[Unit]
  def saveImageInfo(info: ImageInfo): Future[Unit]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]]
  def getLatestKeep(url: String): Future[Option[Keep]]
  def saveBookmark(bookmark: Keep): Future[Keep]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def isUnscrapableP(url: URI, destinationUrl: Option[String]): Future[Boolean]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]
}
