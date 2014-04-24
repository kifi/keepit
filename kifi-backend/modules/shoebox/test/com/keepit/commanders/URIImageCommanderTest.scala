package com.keepit.commanders

import org.specs2.mutable.SpecificationLike
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import scala.concurrent._
import scala.concurrent.duration._
import com.keepit.akka.TestKitScope
import com.google.inject.Injector
import com.keepit.controllers.RequestSource
import scala.Some
import com.keepit.common.store.{FakeS3URIImageStore, ImageSize, ShoeboxFakeStoreModule}
import com.keepit.common.embedly.{EmbedlyExtractResponse, EmbedlyClient}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.Id
import scala.concurrent.ExecutionContext.Implicits.global
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import com.keepit.common.pagepeeker.{PagePeekerImage, PagePeekerClient}

object URIImageCommanderTestDummyValues {
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
  val dummyEmbedlyImage = dummyImage.copy(provider = Some(ImageProvider.EMBEDLY), url = Some(dummyEmbedlyImageUrl))
  val dummyPagePeekerImage = dummyImage.copy(provider = Some(ImageProvider.PAGEPEEKER), url = Some(dummyPagePeekerImageUrl))

  val dummyBufferedImage = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
}

class URIImageCommanderTestEmbedlyClient extends EmbedlyClient {
  def embedlyUrl(url: String): String = ???
  def getAllImageInfo(uri: NormalizedURI, size: ImageSize): Future[Seq[ImageInfo]] = future{Seq(URIImageCommanderTestDummyValues.dummyEmbedlyImage)}
  def getExtractResponse(uri:NormalizedURI):Future[Option[EmbedlyExtractResponse]] = ???
  def getExtractResponse(url:String, uriOpt:Option[NormalizedURI] = None):Future[Option[EmbedlyExtractResponse]] = ???
}

class URIImageCommanderTestPagePeekerClient extends PagePeekerClient {
  def getScreenshotData(url: String): Future[Seq[PagePeekerImage]] = future{Seq(PagePeekerImage(URIImageCommanderTestDummyValues.dummyBufferedImage, ImageSize(1000, 1000)))}
}

class URIImageCommanderTestImageFetcher extends ImageFetcher {
  override def fetchRawImage(url: String): Future[Option[BufferedImage]] = future{Some(URIImageCommanderTestDummyValues.dummyBufferedImage)}
}

class URIImageCommanderTest extends TestKitScope with SpecificationLike with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val imageInfoRepo = inject[ImageInfoRepo]
    val normalizedUriRepo = inject[NormalizedURIRepo]

    db.readWrite { implicit session =>
      val nUri1 = normalizedUriRepo.internByUri("http://www.adomain.com")
      val nUri2 = normalizedUriRepo.internByUri("http://www.anotherdomain.com")
      val image1 = imageInfoRepo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.testimg.com/test.jpg"),
        width = Some(200),
        height = Some(100),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(0)
      ))
      val image2 = imageInfoRepo.save(ImageInfo(
        uriId = nUri2.id.get,
        url = Some("http://www.google.com/test2.jpg"),
        width = Some(1500),
        height = Some(3500),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(1)
      ))
      val image3 = imageInfoRepo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test3.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.PAGEPEEKER),
        format = Some(ImageFormat.JPG),
        priority = Some(0)
      ))
      val image4 = imageInfoRepo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test2.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(3)
      ))
      val image5 = imageInfoRepo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.google.com/test4.jpg"),
        width = Some(2000),
        height = Some(6000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(4)
      ))

      (image1, image2, image3, image4, image5)
    }

  }

  case class URIImageCommanderTestModule() extends ScalaModule {
    def configure() {
      bind[EmbedlyClient].to[URIImageCommanderTestEmbedlyClient]
      bind[PagePeekerClient].to[URIImageCommanderTestPagePeekerClient]
      bind[ImageFetcher].to[URIImageCommanderTestImageFetcher]
    }
  }

  val modules = Seq(
    ShoeboxFakeStoreModule(),
    URIImageCommanderTestModule())

  "URIImageCommander" should {

    withDb(modules:_*) { implicit injector =>
      "get image for url request" in {
        val (image1, image2, image3, image4, image5) = setup()
        val uriImageCommander = inject[URIImageCommander]

        val request1 = URIImageRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result1 = Await.result(uriImageCommander.getImageForURLRequest(request1), 1 seconds)
        result1 must beSome(image4)

        val request2 = URIImageRequest("http://www.anotherdomain.com", ImageType.IMAGE, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result2 = Await.result(uriImageCommander.getImageForURLRequest(request2), 1 seconds)
        result2 must beSome(image2)

        val request3 = URIImageRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(10000, 10000), RequestSource.EXTENSION, false, true)
        val result3 = Await.result(uriImageCommander.getImageForURLRequest(request3), 1 seconds)
        result3 must beNone

        val request4 = URIImageRequest("http://www.notexistingdomain.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, false, true)
        val result4 = Await.result(uriImageCommander.getImageForURLRequest(request4), 1 seconds)
        result4 must beNone

        val request5 = URIImageRequest("http://www.adomain.com", ImageType.SCREENSHOT, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result5 = Await.result(uriImageCommander.getImageForURLRequest(request5), 1 seconds)
        result5 must beSome(image3)
      }

      "not fetch anything" in {

        val uriImageCommander = inject[URIImageCommander]

        val request = URIImageRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, true, true)
        val result = Await.result(uriImageCommander.getImageForURLRequest(request), 1 seconds)
        result must beNone
      }

      val embedlyRequest = URIImageRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, true, false)
      val pagePeekerRequest = URIImageRequest("http://www.notexistingdomain3.com", ImageType.SCREENSHOT, ImageSize(10, 10), RequestSource.EXTENSION, true, false)

      "fetch image from embedly" in {
        val uriImageCommander = inject[URIImageCommander]

        val result = Await.result(uriImageCommander.getImageForURLRequest(embedlyRequest), 1 seconds)
        result must beSome
        val image = result.get
        image.url must beSome(URIImageCommanderTestDummyValues.dummyEmbedlyImageUrl)
      }

      "fetch image from pagepeeker" in {
        val uriImageCommander = inject[URIImageCommander]

        val result = Await.result(uriImageCommander.getImageForURLRequest(pagePeekerRequest), 1 seconds)
        result must beSome
        val image = result.get
        image.url must beSome(FakeS3URIImageStore.placeholderImageURL) // default url provided by TestS3URIImageStore (the pagepeeker client only provides raw data without any url)
      }

      "find any kind of image" in {
        val uriImageCommander = inject[URIImageCommander]

        val embedlyRequestWithAny = embedlyRequest.copy(imageType = ImageType.ANY)
        val embedlyResult = Await.result(uriImageCommander.getImageForURLRequest(embedlyRequestWithAny), 1 seconds)
        embedlyResult must beSome
        val embedlyImage = embedlyResult.get
        embedlyImage.url must beSome(URIImageCommanderTestDummyValues.dummyEmbedlyImageUrl)

        val pagePeekerRequestWithAny = pagePeekerRequest.copy(imageType = ImageType.ANY)
        val pagePeekerResult = Await.result(uriImageCommander.getImageForURLRequest(pagePeekerRequestWithAny), 1 seconds)
        pagePeekerResult must beSome
        val pagePeekerImage = pagePeekerResult.get
        pagePeekerImage.url must beSome("http://www.testurl.com/testimage.jpg") // default url provided by TestS3URIImageStore (the pagepeeker client only provides raw data without any url)
      }
    }
  }
}
