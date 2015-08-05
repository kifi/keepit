package com.keepit.common.store

import com.keepit.commanders.ProcessImageRequest
import com.keepit.model._
import play.api.libs.json._

case class ImagePath(path: String) extends AnyVal {
  override def toString() = path
  def getUrl(implicit imageConfig: S3ImageConfig): String = imageConfig.cdnBase + "/" + path
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
