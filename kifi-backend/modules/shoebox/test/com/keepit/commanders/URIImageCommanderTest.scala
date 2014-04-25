package com.keepit.commanders

import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import scala.concurrent._
import com.google.inject.Injector
import com.keepit.controllers.RequestSource
import com.keepit.common.store.FakeS3URIImageStore
import com.keepit.common.embedly.{EmbedlyExtractResponse, EmbedlyClient}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.Id
import scala.concurrent.ExecutionContext.Implicits.global
import java.awt.image.BufferedImage
import com.keepit.common.images.ImageFetcher
import com.keepit.common.pagepeeker.PagePeekerClient
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import scala.Some
import com.keepit.common.store.ImageSize
import com.keepit.common.pagepeeker.PagePeekerImage
import com.keepit.common.store.ShoeboxFakeStoreModule

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

class URIImageCommanderTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {

    db.readWrite { implicit session =>
      val nUri1 = uriRepo.internByUri("http://www.adomain.com")
      val nUri2 = uriRepo.internByUri("http://www.anotherdomain.com")
      val image1 = imageInfo.save(ImageInfo(
        uriId = nUri1.id.get,
        url = Some("http://www.testimg.com/test.jpg"),
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
        url = Some("http://www.google.com/test2.jpg"),
        width = Some(1000),
        height = Some(3000),
        size = Some(4242),
        provider = Some(ImageProvider.EMBEDLY),
        format = Some(ImageFormat.JPG),
        priority = Some(3)
      ))
      val image5 = imageInfo.save(ImageInfo(
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
    URIImageCommanderTestModule()
  )

  "URIImageCommander" should {

    "find image from repo" in {
      withDb(modules: _*) { implicit injector =>
        val (image1, image2, image3, image4, image5) = setup()
        val uriImageCommander = inject[URIImageCommander]

        // get image for url request
        val request1 = URIImageRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result1fut = uriImageCommander.getImageForURLRequest(request1)
        result1fut must beSome(image4).await

        val request2 = URIImageRequest("http://www.anotherdomain.com", ImageType.IMAGE, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result2fut = uriImageCommander.getImageForURLRequest(request2)
        result2fut must beSome(image2).await

        val request3 = URIImageRequest("http://www.adomain.com", ImageType.IMAGE, ImageSize(10000, 10000), RequestSource.EXTENSION, false, true)
        val result3fut = uriImageCommander.getImageForURLRequest(request3)
        result3fut must beNone.await

        val request4 = URIImageRequest("http://www.notexistingdomain.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, false, true)
        val result4fut = uriImageCommander.getImageForURLRequest(request4)
        result4fut must beNone.await

        val request5 = URIImageRequest("http://www.adomain.com", ImageType.SCREENSHOT, ImageSize(800, 800), RequestSource.EXTENSION, false, true)
        val result5fut = uriImageCommander.getImageForURLRequest(request5)
        result5fut must beSome(image3).await

        // should give no results
        val request = URIImageRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, true, true)
        val result6Fut = uriImageCommander.getImageForURLRequest(request)
        result6Fut must beNone.await
      }
    }

    "find image from clients" in {
      withDb(modules: _*) { implicit injector =>
        val uriImageCommander = inject[URIImageCommander]
        val embedlyRequest = URIImageRequest("http://www.notexistingdomain2.com", ImageType.IMAGE, ImageSize(10, 10), RequestSource.EXTENSION, true, false)
        val pagePeekerRequest = URIImageRequest("http://www.notexistingdomain3.com", ImageType.SCREENSHOT, ImageSize(10, 10), RequestSource.EXTENSION, true, false)

        type Partial = PartialFunction[Option[ImageInfo], MatchResult[_]]

        // fetch image from embedly
        val result7Fut = uriImageCommander.getImageForURLRequest(embedlyRequest)
        result7Fut must beLike({ case Some(result: ImageInfo) =>
          result.url must beSome(URIImageCommanderTestDummyValues.dummyEmbedlyImageUrl)
        }: Partial).await

        // fetch image from pagepeeker
        val result8Fut = uriImageCommander.getImageForURLRequest(pagePeekerRequest)
        result8Fut must beLike({ case Some(result: ImageInfo) =>
          result.url must beSome(FakeS3URIImageStore.placeholderImageURL)
        }: Partial).await

        // find any kind of image
        val embedlyRequestWithAny = embedlyRequest.copy(imageType = ImageType.ANY)
        val embedlyImageFut = uriImageCommander.getImageForURLRequest(embedlyRequestWithAny)
        embedlyImageFut must beLike({ case Some(embedlyImage: ImageInfo) =>
          embedlyImage.url must beSome(URIImageCommanderTestDummyValues.dummyEmbedlyImageUrl)
        }: Partial).await

        val pagePeekerRequestWithAny = pagePeekerRequest.copy(imageType = ImageType.ANY)
        val pagePeekerResultFut = uriImageCommander.getImageForURLRequest(pagePeekerRequestWithAny)
        pagePeekerResultFut must beLike({ case Some(pagePeekerImage: ImageInfo) =>
          pagePeekerImage.url must beSome(FakeS3URIImageStore.placeholderImageURL)
        }: Partial).await
      }
    }
  }
}
