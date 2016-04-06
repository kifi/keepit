package com.keepit.common.store

import com.keepit.commanders.ProcessImageRequest
import com.keepit.model._
import play.api.libs.json._

// ImagePath is RELATIVE to the cdn base
// When images are sent in between services (or persisted to the db) they should be persisted as an ImagePath
// When images are sent down to clients, they should be sent down as an ImageUrl, which includes the cdn base
case class ImagePath(path: String) extends AnyVal {
  override def toString = path
  def getImageUrl(implicit imageConfig: S3ImageConfig): ImageUrl = ImageUrl(imageConfig.cdnBase + "/" + path)
  def getUrl(implicit imageConfig: S3ImageConfig): String = getImageUrl.value
}

object ImagePath {
  def apply(prefix: String, hash: ImageHash, size: ImageSize, kind: ProcessImageOperation, format: ImageFormat): ImagePath = {
    val fileName = hash.hash + "_" + size.width + "x" + size.height + kind.fileNameSuffix + "." + format.value
    ImagePath(prefix + "/" + fileName)
  }

  def apply(prefix: String, hash: ImageHash, process: ProcessImageRequest, format: ImageFormat): ImagePath = {
    val fileName = hash.hash + "_" + process.pathFragment + "." + format.value
    ImagePath(prefix + "/" + fileName)
  }

  implicit val format: Format[ImagePath] = Format(__.read[String].map(ImagePath(_)), Writes(path => JsString(path.path)))
}

final case class ImageUrl(value: String) extends AnyVal
object ImageUrl {
  implicit val writes: Writes[ImageUrl] = Writes(imgUrl => JsString(imgUrl.value))
}

object StaticImageUrls {
  val SLACK_LOGO = "https://djty7jcqog9qu.cloudfront.net/oa/98c4c6dc6bf8aeca952d2316df5b242b_200x200-0x0-200x200_cs.png"
  val KIFI_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png"
  val TWITTER_LOGO = "https://d1dwdv9wd966qu.cloudfront.net/img/twitter_logo_104.png"
}
