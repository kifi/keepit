package com.keepit.controllers.website

import controllers.AssetsBuilder
import play.api.mvc.{AnyContent, Action, Controller}
import play.api.Play
import play.api.libs.iteratee.Enumerator
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.logging.Logging
import play.utils.UriEncoding
import java.io.File


object AngularDistAssets extends AssetsBuilder with Controller with Logging {
  def home = Action {
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream("angular/index.html").get)) as HTML
  }

  override def at(path: String, file: String): Action[AnyContent] = {
    log.info(s"Angular dist resource requested: $path, $file")
    val rName = rNameAt(path, file)
    
    log.info("Resource: " + rName)
    log.info("Play resource: " + rName.map(Play.resource))
    super.at(path, file)
  }

  private def rNameAt(path: String, file: String): Option[String] = {
    val decodedFile = UriEncoding.decodePath(file, "utf-8")
    val resourceName = Option(path + "/" + decodedFile).map(name => if (name.startsWith("/")) name else ("/" + name)).get
    if (new File(resourceName).isDirectory || !new File(resourceName).getCanonicalPath.startsWith(new File(path).getCanonicalPath)) {
      None
    } else {
      Some(resourceName)
    }
  }
}
object AngularImgAssets extends AssetsBuilder with Logging {
  override def at(path: String, file: String): Action[AnyContent] = {
    log.info(s"Angular img resource requested: $path, $file")
    val rName = rNameAt(path, file)
    log.info("Resource: " + rName)
    log.info("Play resource: " + rName.map(Play.resource))
    super.at(path, file)
  }

  private def rNameAt(path: String, file: String): Option[String] = {
    val decodedFile = UriEncoding.decodePath(file, "utf-8")
    val resourceName = Option(path + "/" + decodedFile).map(name => if (name.startsWith("/")) name else ("/" + name)).get
    if (new File(resourceName).isDirectory || !new File(resourceName).getCanonicalPath.startsWith(new File(path).getCanonicalPath)) {
      None
    } else {
      Some(resourceName)
    }
  }
}
