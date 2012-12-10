package com.keepit.common.net

import com.keepit.common.logging.Logging

object URINormalizer extends Logging {

  val normalizers = Seq(AmazonNormalizer, GoogleNormalizer, RemoveWWWNormalizer, DefaultNormalizer)

  def normalize(uriString: String) = {
    //better: use http://stackoverflow.com/a/4057470/81698
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

  object AmazonNormalizer extends Normalizer {
    val product = """(.*)(/dp/[^/]+)(/.*)""".r
    val product2 = """(.*/gp/product)(/[^/]+)(/.*)""".r
    val productReviews = """(.*)(/product-reviews/[^/]+)(/.*)""".r
    val profile = """(.*)(/gp/pdp/profile/[^/]+)(/.*)""".r
    val memberReviews = """(.*)(/gp/cdp/member-reviews/[^/]+)(/.*)""".r
    val wishlist = """(.*)(/wishlist/[^/]+)(/.*)""".r

    def isDefinedAt(uri: URI) = {
      (uri.host match {
        case Some(Host("com", "amazon")) => true
        case Some(Host("com", "amazon", "www")) => true
        case _ => false
      }) && (uri.path match {
        case Some(product(_, _, _)) => true
        case Some(product2(_, _, _)) => true
        case Some(productReviews(_, _, _)) => true
        case Some(profile(_, _, _)) => true
        case Some(memberReviews(_, _, _)) => true
        case Some(wishlist(_, _, _)) => true
        case _ => false
      })
    }
    def apply(uri: URI) = {
      uri match {
        case URI(scheme, userInfo, host, port, path, _, _) =>
          path match {
            case Some(product(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None).toString
            case Some(product2(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some("/dp"+ key), None, None).toString
            case Some(productReviews(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None).toString
            case Some(profile(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None).toString
            case Some(memberReviews(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None).toString
            case Some(wishlist(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None).toString
            case _ => DefaultNormalizer(uri)
          }
        case _ => throw new Exception("illegal invocation")
      }
    }

    def normalize(host: Option[Host]) = {
      host match {
        case Some(Host("com", "amazon")) => Some(Host("com", "amazon", "www"))
        case _ => host
      }
    }
  }

  object GoogleNormalizer extends Normalizer {

    def isDefinedAt(uri: URI) = {
      uri.host match {
        case Some(Host("com", "google", name)) =>
          name match {
            case "mail" => true
            case "groups" => true
            case "drive" => true
            case "docs" => true
            case _ => false
          }
        case _ => false
      }
    }
    val regex = """(.*)(/document/d/[^/]+/)(.*)""".r
    def apply(uri: URI) = {
      uri match {
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(regex(_, docKey, _)), query, fragment) =>
          URI(scheme, userInfo, host, port, Some(docKey + "edit"), query, fragment).toString
        case _ => uri.raw.get // expects the raw uri string is always there
      }
    }
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

  object RemoveWWWNormalizer extends Normalizer {
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
}
