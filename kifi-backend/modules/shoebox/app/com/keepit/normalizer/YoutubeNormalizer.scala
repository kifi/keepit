package com.keepit.normalizer

import com.keepit.common.net.{ Query, Host, URI }

object YoutubeNormalizer extends StaticNormalizer {
  def isDefinedAt(uri: URI) = {
    uri match {
      case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) =>
        path.endsWith("/watch") && query.containsParam("v")
      case _ => false
    }
  }
  def apply(uri: URI) = {
    uri match {
      case URI(scheme, _, host, port, path, Some(query), _) =>
        val newQuery = Query(query.params.filter { _.name == "v" })
        URI(scheme, None, host, port, path, Some(newQuery), None)
      case _ => throw new Exception("illegal invocation")
    }
  }
}
