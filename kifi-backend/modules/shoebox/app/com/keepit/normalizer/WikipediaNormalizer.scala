package com.keepit.normalizer

import com.keepit.common.net.{ Host, URI }
import com.keepit.model.Normalization

object WikipediaNormalizer extends StaticNormalizer {
  def isDefinedAt(uri: URI) = {
    uri.host match {
      case Some(Host(_, "wikipedia", _*)) => true
      case _ => false
    }
  }
  def apply(uri: URI) = {
    val desktopUri = uri match {
      case URI(scheme, userInfo, Some(Host(ext, "wikipedia", "m", lang)), port, path, query, fragment) =>
        URI(scheme, userInfo, Some(Host(ext, "wikipedia", lang)), port, path, query, fragment)
      case _ => uri
    }
    DefaultNormalizer(SchemeNormalizer(Normalization.HTTPS)(desktopUri))
  }
}
