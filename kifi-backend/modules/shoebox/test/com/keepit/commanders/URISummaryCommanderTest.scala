package com.keepit.commanders

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.net.URI
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import scala.concurrent._
import com.google.inject.Injector
import com.keepit.common.store._
import com.keepit.scraper.embedly._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.Id
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import akka.actor.Scheduler
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.scraper._
import com.google.inject.{ Singleton, Provides }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.search.{ ArticleStore, InMemoryArticleStoreImpl }

object URISummaryCommanderTestDummyValues {
  val dummyImage = ImageInfo(
    uriId = Id[NormalizedURI](2873),
    url = None,
    width = Some(200),
    height = Some(100),
    size = Some(4242),
    provider = None,
    format = Some(ImageFormat.JPG),
    priority = Some(0),
    path = ImagePath("dummy.jpg")
  );
  val dummyEmbedlyImageUrl = "http://localhost/S3_KEY.jpg"
  val dummyPagePeekerImageUrl = "http://www.testimg.com/thedummypagepeekerscreenshot.jpg"
  val dummyPagePeekerImage = dummyImage.copy(provider = Some(ImageProvider.PAGEPEEKER), url = Some(dummyPagePeekerImageUrl))

  val dummyBufferedImage = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
}

class URISummaryCommanderTestImageFetcher extends ImageFetcher {
  override def fetchRawImage(url: URI): Future[Option[BufferedImage]] = Future.successful(Some(URISummaryCommanderTestDummyValues.dummyBufferedImage))
}

case class MockScraperServiceClient(override val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends FakeScraperServiceClientImpl(airbrakeNotifier, scheduler) {
  override def fetchAndPersistURIPreview(url: String): Future[Option[URIPreviewFetchResult]] = Future.successful {
    Some(URIPreviewFetchResult(url, Some("title"), Some("desc"), Seq.empty, None, Some(true), None, None, Some(PersistedImageRef(Seq(PersistedImageVersion(100, 100, ImagePath("S3_KEY.jpg"), "https://original.url/image.jpg")), None))))
  }
}

class URISummaryCommanderTest extends Specification with ShoeboxTestInjector {

  private def getCDNURL(cdnBase: String, info: ImageInfo): String = {
    cdnBase + "/" + info.path
  }

  def setup()(implicit injector: Injector) = {
    val cdnBase = inject[S3ImageConfig].cdnBase

    db.readWrite { implicit session =>
      val nUri1 = normalizedURIInterner.internByUri("http://www.adomain.com")
      val nUri2 = normalizedURIInterner.internByUri("http://www.anotherdomain.com")
      val nUri3 = normalizedURIInterner.internByUri("http://www.anotherdomain2.com")
      val nUri4 = normalizedURIInterner.internByUri("http://www.anotherdomain3.com")
      val image1 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test1.jpg"),
        width = Some(200),
        height = Some(100),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(0),
        path = ImagePath("foo.jpg")
      ))
      val image2 = imageInfo.save(ImageInfo(
        uriId = nUri2.id.get,
        url = Some("http://www.google.com/test2.jpg"),
        width = Some(1500),
        height = Some(3500),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(1),
        path = ImagePath("bar.jpg")
      ))
      val image3 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test3.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.PAGEPEEKER),
        format = Some(ImageFormat.JPG),
        priority = Some(0),
        path = ImagePath("dar.jpg")
      ))
      val image4 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test4.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(3),
        path = ImagePath("zar.jpg")
      ))
      val image5 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test5.jpg"),
        width = Some(2000),
        height = Some(6000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(4),
        path = ImagePath("gar.jpg")
      ))

      (getCDNURL(cdnBase, image1), getCDNURL(cdnBase, image2), getCDNURL(cdnBase, image3), getCDNURL(cdnBase, image4), getCDNURL(cdnBase, image5), nUri1, nUri2, nUri3, nUri4)
    }

  }

  case class URISummaryCommanderTestModule() extends ScalaModule {
    def configure() {
      bind[ImageFetcher].to[URISummaryCommanderTestImageFetcher]
    }

    @Singleton
    @Provides
    def scraperClient(): ScraperServiceClient = {
      MockScraperServiceClient(null, null)
    }

    @Singleton @Provides
    def embedlyStore(): EmbedlyStore = {
      new InMemoryEmbedlyStoreImpl()
    }

    @Singleton @Provides
    def articleStore(): ArticleStore = new InMemoryArticleStoreImpl()
  }

  val modules = Seq(
    FakeExecutionContextModule(),
    URISummaryCommanderTestModule(),
    FakeCortexServiceClientModule()
  )

  "URISummaryCommander" should {

    "find image from repo" in {
      withDb(modules: _*) { implicit injector =>
        val (imageUrl1, imageUrl2, imageUrl3, imageUrl4, imageUrl5, nUri1, nUri2, nUri3, nUri4) = setup()
        val URISummaryCommander = inject[URISummaryCommander]

        // get image for url request
        val request1 = URISummaryRequest(nUri1.id.get, ImageType.IMAGE, ImageSize(800, 800), false, true, false)
        val result1fut = URISummaryCommander.getURISummaryForRequest(request1) map { _.imageUrl }
        result1fut must beSome(imageUrl4).await

        val request2 = URISummaryRequest(nUri2.id.get, ImageType.IMAGE, ImageSize(800, 800), false, false, true)
        val result2fut = URISummaryCommander.getURISummaryForRequest(request2) map { _.imageUrl }
        result2fut must beSome(imageUrl2).await

        val request3 = URISummaryRequest(nUri1.id.get, ImageType.IMAGE, ImageSize(10000, 10000), false, false, true)
        val result3fut = URISummaryCommander.getURISummaryForRequest(request3) map { _.imageUrl }
        result3fut must beNone.await

        val request4 = URISummaryRequest(nUri3.id.get, ImageType.IMAGE, ImageSize(10, 10), false, false, true)
        val result4fut = URISummaryCommander.getURISummaryForRequest(request4) map { _.imageUrl }
        result4fut must beNone.await

        val request5 = URISummaryRequest(nUri1.id.get, ImageType.SCREENSHOT, ImageSize(800, 800), false, false, true)
        val result5fut = URISummaryCommander.getURISummaryForRequest(request5) map { _.imageUrl }
        result5fut must beSome(imageUrl3).await

        // should give no results
        val request = URISummaryRequest(nUri4.id.get, ImageType.IMAGE, ImageSize(10, 10), false, true, true)
        val result6Fut = URISummaryCommander.getURISummaryForRequest(request) map { _.imageUrl }
        result6Fut must beNone.await
      }
    }

    "find image from clients" in {
      withDb(modules: _*) { implicit injector =>
        val uriSummaryCommander = inject[URISummaryCommander]
        val (nUri1, nUri2) = db.readWrite { implicit session =>
          val nUri1 = normalizedURIInterner.internByUri("http://www.adomain.com")
          val nUri2 = normalizedURIInterner.internByUri("http://www.anotherdomain.com")
          (nUri1, nUri2)
        }
        val embedlyRequest = URISummaryRequest(nUri1.id.get, ImageType.IMAGE, ImageSize(10, 10), false, true, false)

        type Partial = PartialFunction[Option[String], MatchResult[_]]

        // fetch image from embedly
        val result7Fut = uriSummaryCommander.getURISummaryForRequest(embedlyRequest) map { _.imageUrl }
        result7Fut must beLike({
          case Some(result: String) =>
            result === URISummaryCommanderTestDummyValues.dummyEmbedlyImageUrl
        }: Partial).await

        // find any kind of image
        val embedlyRequestWithAny = embedlyRequest.copy(imageType = ImageType.ANY)
        val embedlyImageFut = uriSummaryCommander.getURISummaryForRequest(embedlyRequestWithAny) map { _.imageUrl }
        embedlyImageFut must beLike({
          case Some(result: String) =>
            result === URISummaryCommanderTestDummyValues.dummyEmbedlyImageUrl
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