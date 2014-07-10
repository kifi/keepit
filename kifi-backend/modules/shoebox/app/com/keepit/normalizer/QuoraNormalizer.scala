package com.keepit.normalizer

import com.keepit.common.net.{ Param, Query, Host, URI }

object QuoraNormalizer extends StaticNormalizer {
  def isDefinedAt(uri: URI) = {
    uri.host match {
      case Some(Host("com", "quora", _*)) => true
      case _ => false
    }
  }
  def apply(uri: URI) = {
    val share = Param("share", Some("1"))
    uri match {
      case URI(scheme, userInfo, host @ Some(Host("com", "quora", subdomain @ _*)), port, path, _, _) if (subdomain.isEmpty || subdomain == List("www")) && path.nonEmpty =>
        URI(Some("https"), userInfo, host, port, path, Some(Query(Seq(share))), None)
      case URI(scheme, userInfo, host, port, path, _, _) =>
        URI(Some("https"), userInfo, host, port, path, None, None)
    }
  }
}
