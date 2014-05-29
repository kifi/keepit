package com.keepit.normalizer

import com.keepit.common.logging.Logging
import com.keepit.common.net.{Query, URI}

object DefaultNormalizer extends StaticNormalizer with Logging {

  val stopParams = Set(
    "jsessionid", "phpsessid", "aspsessionid",
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "trk", "goback", "icid", "ncid", "zenid")

  def isDefinedAt(uri: URI) = true // default normalizer should always be applicable
  def apply(uri: URI) = {
    uri match {
      case URI(scheme, userInfo, host, port, path, query, fragment) =>
        try {
          val newQuery = query.flatMap{ query =>
            val newParams = query.params.filter{ param => !stopParams.contains(param.name) }
            if (newParams.isEmpty) None else Some(Query(newParams))
          }
          if (fragment.isDefined && fragment.get != "/" && (fragment.get.contains("/") || fragment.get.contains("%2F"))){
            URI(scheme, userInfo, host, port, path, newQuery, fragment)
          }
          else URI(scheme, userInfo, host, port, path, newQuery, None)
        } catch {
          case e: Exception =>
            log.warn("uri normalization failed: [%s] caused by [%s]".format(uri.raw, e.getMessage))
            uri
        }
      case _ => throw new Exception("illegal invocation")
    }
  }
}
