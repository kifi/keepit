package com.keepit.commanders

import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import scala.concurrent._
import com.google.inject.Injector
import com.keepit.common.store.{S3URIImageStore, FakeS3URIImageStore, ImageSize}
import com.keepit.scraper.embedly._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.Id
import scala.concurrent.ExecutionContext.Implicits.global
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import com.keepit.common.pagepeeker.PagePeekerClient
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import scala.Some
import com.keepit.common.pagepeeker.PagePeekerImage
import scala.util.{Success, Try}
import akka.actor.Scheduler
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper.FakeScraperServiceClientImpl
import com.keepit.scraper.ScraperServiceClient
import com.google.inject.{Singleton, Provides}

object URISummaryCommanderTestDummyValues {
  val dummyImage = ImageInfo(
    uriId = Id[NormalizedURI](2873),
    url = None,
    width = Some(200),
    height = Some(100),
    size = Some(4242),
    provider = None,
    format = Some(ImageFormat.JPG),
    priority = Some(0)
  );
  val dummyEmbedlyImageUrl = "http://www.testimg.com/thedummyembedlyimage.jpg"
  val dummyPagePeekerImageUrl = "http://www.testimg.com/thedummypagepeekerscreenshot.jpg"
  private val embedlyImage = EmbedlyImage(dummyEmbedlyImageUrl, None, Some(200), Some(100), Some(4242))
  val dummyEmbedlyInfo = ExtendedEmbedlyInfo.EMPTY.copy(images = Seq(embedlyImage))
  val dummyPagePeekerImage = dummyImage.copy(provider = Some(ImageProvider.PAGEPEEKER), url = Some(dummyPagePeekerImageUrl))

  val dummyBufferedImage = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
}

class URISummaryCommanderTestPagePeekerClient extends PagePeekerClient {
  override def getScreenshotData(normalizedUri: NormalizedURI): Future[Option[Seq[PagePeekerImage]]] =
    future{Some(Seq(PagePeekerImage(URISummaryCommanderTestDummyValues.dummyBufferedImage, ImageSize(1000, 1000))))}
}

class URISummaryCommanderTestImageFetcher extends ImageFetcher {
  override def fetchRawImage(url: String): Future[Option[BufferedImage]] = Future.successful(Some(URISummaryCommanderTestDummyValues.dummyBufferedImage))
}

case class URISummaryCommanderTestS3URIImageStore() extends S3URIImageStore {
  def storeImage(info: ImageInfo, rawImage: BufferedImage, nUri: NormalizedURI): Try[(String, Int)] = Success((FakeS3URIImageStore.placeholderImageURL, FakeS3URIImageStore.placeholderSize))
  def getDefaultScreenshotURL(nUri: NormalizedURI): Option[String] = Some(FakeS3URIImageStore.placeholderScreenshotURL)
  def getImageURL(imageInfo: ImageInfo, nUri: NormalizedURI): Option[String] = imageInfo.url // returns the original ImageInfo url (important!)
}

case class MockScraperServiceClient(override val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends FakeScraperServiceClientImpl(airbrakeNotifier, scheduler) {
  override def getEmbedlyInfo(url: String): Future[Option[ExtendedEmbedlyInfo]] = Future.successful(Some(URISummaryCommanderTestDummyValues.dummyEmbedlyInfo))
  override def getURISummaryFromEmbedly(uri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]] = {
    val embedlyInfo = URISummaryCommanderTestDummyValues.dummyEmbedlyInfo
    val summary = Some(URISummary(Some(URISummaryCommanderTestDummyValues.dummyEmbedlyImageUrl), embedlyInfo.title, embedlyInfo.description))
    Future.successful(summary)
  }
}

class URISummaryCommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {

    db.readWrite { implicit session =>
      val nUri1 = uriRepo.internByUri("http://www.adomain.com")
      val nUri2 = uriRepo.internByUri("http://www.anotherdomain.com")
      val image1 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test1.jpg"),
        width = Some(200),
        height = Some(100),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(0)
      ))
      val image2 = imageInfo.save(ImageInfo(
        uriId = nUri2.id.get,
        url = Some("http://www.google.com/test2.jpg"),
        width = Some(1500),
        height = Some(3500),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(1)
      ))
      val image3 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test3.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.PAGEPEEKER),
        format = Some(ImageFormat.JPG),
        priority = Some(0)
      ))
      val image4 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test4.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(3)
      ))
      val image5 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test5.jpg"),
        width = Some(2000),
        height = Some(6000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(4)
      ))

      (image1.url.get, image2.url.get, image3.url.get, image4.url.get, image5.url.get)
    }

  }

  case class URISummaryCommanderTestModule() extends ScalaModule {
    def configure() {
      bind[PagePeekerClient].to[URISummaryCommanderTestPagePeekerClient]
      bind[ImageFetcher].to[URISummaryCommanderTestImageFetcher]
      bind[S3URIImageStore].to[URISummaryCommanderTestS3URIImageStore]
    }

    @Singleton
    @Provides
    def scraperClient(): ScraperServiceClient = {
      MockScraperServiceClient(null, null)
    }
  }

  val modules = Seq(
    URISummaryCommanderTestModule()
  )

  "URISummaryCommander" should {

    "find image from repo" in {
      withDb(modules: _*) { implicit injector =>
        val (imageUrl1, imageUrl2, imageUrl3, imageUrl4, imageUrl5) = setup()
        val URISummaryCommander = inject[URISummaryCommander]

        // get image for url request
        val request1 = URISummaryRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(800, 800), false, true, false)
        val result1fut = URISummaryCommander.getURISummaryForRequest(request1) map { _.imageUrl }
        result1fut must beSome(imageUrl4).await

        val request2 = URISummaryRequest("http://www.anotherdomain.com", ImageType.IMAGE, ImageSize(800, 800), false, false, true)
        val result2fut = URISummaryCommander.getURISummaryForRequest(request2) map { _.imageUrl }
        result2fut must beSome(imageUrl2).await

        val request3 = URISummaryRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(10000, 10000), false, false, true)
        val result3fut = URISummaryCommander.getURISummaryForRequest(request3) map { _.imageUrl }
        result3fut must beNone.await

        val request4 = URISummaryRequest("http://www.notexistingdomain.com", ImageType.IMAGE, ImageSize(10, 10), false, false, true)
        val result4fut = URISummaryCommander.getURISummaryForRequest(request4) map { _.imageUrl }
        result4fut must beNone.await

        val request5 = URISummaryRequest("http://www.adomain.com", ImageType.SCREENSHOT, ImageSize(800, 800), false, false, true)
        val result5fut = URISummaryCommander.getURISummaryForRequest(request5) map { _.imageUrl }
        result5fut must beSome(imageUrl3).await

        // should give no results
        val request = URISummaryRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), false, true, true)
        val result6Fut = URISummaryCommander.getURISummaryForRequest(request) map { _.imageUrl }
        result6Fut must beNone.await
      }
    }

    "find image from clients" in {
      withDb(modules: _*) { implicit injector =>
        val URISummaryCommander = inject[URISummaryCommander]
        val embedlyRequest = URISummaryRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), false, true, false)
        val pagePeekerRequest = URISummaryRequest("http://www.notexistingdomain3.com", ImageType.SCREENSHOT, ImageSize(10, 10), false, true, false)

        type Partial = PartialFunction[Option[String], MatchResult[_]]

        // fetch image from embedly
        val result7Fut = URISummaryCommander.getURISummaryForRequest(embedlyRequest) map { _.imageUrl }
        result7Fut must beLike({ case Some(result: String) =>
          result === URISummaryCommanderTestDummyValues.dummyEmbedlyImageUrl
        }: Partial).await

        // fetch image from pagepeeker
        val result8Fut = URISummaryCommander.getURISummaryForRequest(pagePeekerRequest) map { _.imageUrl }
        result8Fut must beLike({ case Some(result: String) =>
          result === FakeS3URIImageStore.placeholderImageURL
        }: Partial).await

        // find any kind of image
        val embedlyRequestWithAny = embedlyRequest.copy(imageType = ImageType.ANY)
        val embedlyImageFut = URISummaryCommander.getURISummaryForRequest(embedlyRequestWithAny) map { _.imageUrl }
        embedlyImageFut must beLike({ case Some(result: String) =>
          result === URISummaryCommanderTestDummyValues.dummyEmbedlyImageUrl
        }: Partial).await

        val pagePeekerRequestWithAny = pagePeekerRequest.copy(imageType = ImageType.ANY)
        val pagePeekerResultFut = URISummaryCommander.getURISummaryForRequest(pagePeekerRequestWithAny) map { _.imageUrl }
        pagePeekerResultFut must beLike({ case Some(result: String) =>
          result === FakeS3URIImageStore.placeholderImageURL
        }: Partial).await
      }
    }
    // This test doesn't work yet (race condition)
    /*"lazily provide screenshots" in {
      withDb(modules: _*) {
        implicit injector =>
          val URISummaryCommander = inject[URISummaryCommander]
          val nUri = db.readWrite { implicit session =>
            uriRepo.internByUri("http://www.adomain3.com")
          }

          // no screenshot yet
          val result1Fut = URISummaryCommander.getScreenshotURL(nUri)
          result1Fut must beNone

          // now there is one
          val result2Fut = URISummaryCommander.getScreenshotURL(nUri)
          result2Fut must beSome(FakeS3URIImageStore.placeholderScreenshotURL)
      }
    }
    */
  }
}
