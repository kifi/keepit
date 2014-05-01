package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.{Future, Await, Awaitable}
import com.keepit.model._
import com.keepit.common.db.{State, Id}
import scala.concurrent.duration._
import com.keepit.common.concurrent.ExecutionContext

class ShoeboxDbCallbackHelper @Inject() (config:ScraperConfig, shoeboxServiceClient:ShoeboxServiceClient) extends SyncShoeboxDbCallbacks with ShoeboxDbCallbacks {
  implicit val serviceCallTimeout = config.serviceCallTimeout
  implicit val fjCtx = ExecutionContext.fj

  private def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, config.syncAwaitTimeout seconds)

  def syncAssignTasks(zkId:Long, max: Int):Seq[ScrapeRequest] = await(assignTasks(zkId, max))
  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]) = await(isUnscrapableP(url, destinationUrl))
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI] = await(getNormalizedUri(uri))
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI = await(saveNormalizedUri(uri))
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = await(saveScrapeInfo(info))
  def syncSavePageInfo(info:PageInfo):PageInfo = await(savePageInfo(info))
  def syncSaveImageInfo(info:ImageInfo):ImageInfo = await(saveImageInfo(info))
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Keep] = await(getBookmarksByUriWithoutTitle(uriId))
  def syncGetLatestKeep(url: String): Option[Keep] = await(getLatestKeep(url))
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = await(recordPermanentRedirect(uri, redirect))
  def syncSaveBookmark(bookmark:Keep):Keep = await(saveBookmark(bookmark))
  def syncRecordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Unit = {
    await(recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls))
  }

  def assignTasks(zkId:Long, max: Int): Future[Seq[ScrapeRequest]] = shoeboxServiceClient.assignScrapeTasks(zkId, max)
  def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]] = {
    uri.id match {
      case Some(id) => shoeboxServiceClient.getNormalizedURI(id).map(Some(_))
      case None => shoeboxServiceClient.getNormalizedURIByURL(uri.url)
    }
  }
  def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI] = shoeboxServiceClient.saveNormalizedURI(uri)
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo] = shoeboxServiceClient.saveScrapeInfo(if (info.state == ScrapeInfoStates.INACTIVE) info else info.withState(ScrapeInfoStates.ACTIVE))
  def savePageInfo(info:PageInfo):Future[PageInfo] = shoeboxServiceClient.savePageInfo(info)
  def saveImageInfo(info:ImageInfo):Future[ImageInfo] = shoeboxServiceClient.saveImageInfo(info)
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Keep]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)
  def getLatestKeep(url: String): Future[Option[Keep]] = shoeboxServiceClient.getLatestKeep(url)
  def saveBookmark(bookmark:Keep): Future[Keep] = shoeboxServiceClient.saveBookmark(bookmark)
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)
  def isUnscrapableP(url: String, destinationUrl: Option[String]) = shoeboxServiceClient.isUnscrapableP(url, destinationUrl)
  def scraped(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = shoeboxServiceClient.scraped(uri, info)
  def scrapeFailed(uri: NormalizedURI, info: ScrapeInfo) = shoeboxServiceClient.scrapeFailed(uri, info)
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    shoeboxServiceClient.recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls)
  }
  override def updateURIRestriction(uriId: Id[NormalizedURI], r: Option[Restriction]): Unit = await({
    shoeboxServiceClient.updateURIRestriction(uriId, r)
  })
}

trait SyncShoeboxDbCallbacks {
  def syncAssignTasks(zkId:Long, max:Int):Seq[ScrapeRequest]
  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]):Boolean
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI]
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo
  def syncSavePageInfo(info:PageInfo):PageInfo
  def syncSaveImageInfo(info:ImageInfo):ImageInfo
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Keep]
  def syncGetLatestKeep(url: String): Option[Keep]
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI
  def syncSaveBookmark(bookmark:Keep):Keep
  def syncRecordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Unit
  def updateURIRestriction(uriId: Id[NormalizedURI], r: Option[Restriction]): Unit
}

trait ShoeboxDbCallbacks {
  def assignTasks(zkId:Long, max:Int):Future[Seq[ScrapeRequest]]
  def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]]
  def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI]
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo]
  def savePageInfo(info:PageInfo):Future[PageInfo]
  def saveImageInfo(info:ImageInfo):Future[ImageInfo]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Keep]]
  def getLatestKeep(url: String): Future[Option[Keep]]
  def saveBookmark(bookmark:Keep): Future[Keep]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]

  def scraped(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  def scrapeFailed(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  // def updateScrapeInfo(idOpt:Option[Id[ScrapeInfo]], uriId:Id[NormalizedURI], state:State[ScrapeInfo]):Future[ScrapeInfo]
}