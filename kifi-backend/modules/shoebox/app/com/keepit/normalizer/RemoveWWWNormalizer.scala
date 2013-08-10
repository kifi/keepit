package com.keepit.normalizer

import com.keepit.common.net.{Host, URI}

object RemoveWWWNormalizer extends URINormalizer {
  def isDefinedAt(uri: URI) = {
    uri.host match {
      case Some(Host("com", "techcrunch", "www")) => true
      case _ => false
    }
  }
  def apply(uri: URI) = {
    uri match {
      case URI(scheme, userInfo, Some(Host(domain @ _*)), port, path, query, fragment) =>
        DefaultNormalizer(URI(scheme, userInfo, Some(Host(domain.take(domain.size - 1): _*)), port, path, query, fragment))
      case _ => throw new Exception("illegal invocation")
    }
  }
}
