package com.keepit.common.net

import com.keepit.common.logging.Logging

object URINormalizer extends Logging {
  
  val normalizers = Seq(GoogleNormalizer, DefaultNormalizer)
  
  def normalize(uriString: String) = {
    URI.parse(uriString) match {
      case Some(uri) =>
        normalizers.find(_.isDefinedAt(uri)).map{ n =>
          n.apply(uri)
        }.getOrElse(throw new Exception("failed to find a normalizer"))
      case None =>
        log.error("uri normalization failed: [%s]".format(uriString))
        uriString // parsing/normalization failed
    }
  }
  
  trait Normalizer extends PartialFunction[URI, String]
  
  object DefaultNormalizer extends Normalizer {
    
    val stopParams = Set(
      "jsessionid", "phpsessid", "aspsessionid",
      "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
      "zenid")
    
    def isDefinedAt(uri: URI) = true // default normalizer should always be applicable
    def apply(uri: URI) = {
      uri match {
        case URI(scheme, userInfo, host, port, path, query, _) =>
          val newQuery = query.flatMap{ query =>
            val newParams = query.params.filter{ param => !stopParams.contains(param.name) }
            if (newParams.isEmpty) None else Some(Query(newParams))
          }
          URI(scheme, userInfo, host, port, path, newQuery, None).toString
        case _ => throw new Exception("illegal invocation")
      }
    }
  }
  
  object GoogleNormalizer extends Normalizer {
    def isDefinedAt(uri: URI) = {
      uri.host.get match {
        case Host("com", "google", name) =>
          name match {
            case "mail" => true
            case "drive" => true
            case _ => false
          }
        case _ => false
      }
    }
    def apply(uri: URI) = uri.raw.get // expects the raw uri string is always there
  }
  
  object YoutubeNormalizer extends Normalizer {
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
          val newQuery = Query(query.params.filter{ _.name == "v" })
          URI(scheme, None, host, port, path, Some(newQuery), None).toString
        case _ => throw new Exception("illegal invocation")
      }
    }
  }
}