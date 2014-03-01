package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.{Future, Await, Awaitable}
import com.keepit.model._
import com.keepit.common.db.{State, Id}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._

class ShoeboxDbCallbackHelper @Inject() (config:ScraperConfig, shoeboxServiceClient:ShoeboxServiceClient) extends SyncShoeboxDbCallbacks with ShoeboxDbCallbacks {
  implicit val serviceCallTimeout = config.serviceCallTimeout

  private def await[T](awaitable: Awaitable[T]) = Await.result(awaitable, config.syncAwaitTimeout seconds)

  def syncAssignTasks(zkId:Long, max: Int):Seq[ScrapeRequest] = await(assignTasks(zkId, max))
  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]) = await(isUnscrapableP(url, destinationUrl))
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI] = await(getNormalizedUri(uri))
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI = await(saveNormalizedUri(uri))
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo = await(saveScrapeInfo(info))
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark] = await(getBookmarksByUriWithoutTitle(uriId))
  def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark] = await(getLatestBookmark(uriId))
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI = await(recordPermanentRedirect(uri, redirect))
  def syncSaveBookmark(bookmark:Bookmark):Bookmark = await(saveBookmark(bookmark))
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
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]] = shoeboxServiceClient.getBookmarksByUriWithoutTitle(uriId)
  def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]] = shoeboxServiceClient.getLatestBookmark(uriId)
  def saveBookmark(bookmark:Bookmark): Future[Bookmark] = shoeboxServiceClient.saveBookmark(bookmark)
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = shoeboxServiceClient.recordPermanentRedirect(uri, redirect)
  def isUnscrapableP(url: String, destinationUrl: Option[String]) = shoeboxServiceClient.isUnscrapableP(url, destinationUrl)
  def scraped(uri: NormalizedURI, info: ScrapeInfo): Future[Option[NormalizedURI]] = shoeboxServiceClient.scraped(uri, info)
  def scrapeFailed(uri: NormalizedURI, info: ScrapeInfo) = shoeboxServiceClient.scrapeFailed(uri, info)
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    shoeboxServiceClient.recordScrapedNormalization(uriId, uriSignature, candidateUrl, candidateNormalization, alternateUrls)
  }
}

trait SyncShoeboxDbCallbacks {
  def syncAssignTasks(zkId:Long, max:Int):Seq[ScrapeRequest]
  def syncIsUnscrapableP(url: String, destinationUrl: Option[String]):Boolean
  def syncGetNormalizedUri(uri:NormalizedURI):Option[NormalizedURI]
  def syncSaveNormalizedUri(uri:NormalizedURI):NormalizedURI
  def syncSaveScrapeInfo(info:ScrapeInfo):ScrapeInfo
  def syncGetBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Seq[Bookmark]
  def syncGetLatestBookmark(uriId: Id[NormalizedURI]): Option[Bookmark]
  def syncRecordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): NormalizedURI
  def syncSaveBookmark(bookmark:Bookmark):Bookmark
  def syncRecordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Unit

}

trait ShoeboxDbCallbacks {
  def assignTasks(zkId:Long, max:Int):Future[Seq[ScrapeRequest]]
  def getNormalizedUri(uri:NormalizedURI):Future[Option[NormalizedURI]]
  def saveNormalizedUri(uri:NormalizedURI):Future[NormalizedURI]
  def saveScrapeInfo(info:ScrapeInfo):Future[ScrapeInfo]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]):Future[Seq[Bookmark]]
  def getLatestBookmark(uriId: Id[NormalizedURI]): Future[Option[Bookmark]]
  def saveBookmark(bookmark:Bookmark): Future[Bookmark]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]

  def scraped(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  def scrapeFailed(uri:NormalizedURI, info:ScrapeInfo): Future[Option[NormalizedURI]]
  // def updateScrapeInfo(idOpt:Option[Id[ScrapeInfo]], uriId:Id[NormalizedURI], state:State[ScrapeInfo]):Future[ScrapeInfo]
}