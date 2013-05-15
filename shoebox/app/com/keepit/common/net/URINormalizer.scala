package com.keepit.common.net

import com.keepit.common.logging.Logging
import util.{Failure, Success}

object URINormalizer extends Logging {

  val normalizers = Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def normalize(uriString: String) = {
    URI.parse(uriString) match {
      case Success(uri) =>
        normalizers.find(_.isDefinedAt(uri)).map{ n =>
          n.apply(uri)
        }.getOrElse(throw new Exception("failed to find a normalizer"))
      case Failure(_) =>
        log.error("uri normalization failed: [%s]".format(uriString))
        uriString
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
          try {
            val newQuery = query.flatMap{ query =>
              val newParams = query.params.filter{ param => !stopParams.contains(param.name) }
              if (newParams.isEmpty) None else Some(Query(newParams))
            }
            URI(scheme, userInfo, host, port, path, newQuery, None).toString
          } catch {
            case e: Exception =>
              log.warn("uri normalization failed: [%s] caused by [%s]".format(uri.raw, e.getMessage))
              uri.raw.get
          }
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
    val document = """(.*)(/document/d/[^/]+/)(.*)""".r
    val spreadsheet = """(.*)(/spreadsheet/ccc)(.*)""".r
    val file = """(.*)(/file/d/[^/]+/)(.*)""".r
    val gmail = """(/mail/)(.*)""".r

    def apply(uri: URI) = {
      uri match {
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(document(_, docKey, _)), query, fragment) =>
          URI(scheme, userInfo, host, port, Some(docKey + "edit"), query, None).toString
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(file(_, fileKey, _)), query, fragment) =>
          URI(scheme, userInfo, host, port, Some(fileKey + "edit"), query, None).toString
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(spreadsheet(_, spreadKey, _)), Some(query), fragment) =>
          val newQuery = Query(query.params.filter{ q => q.name == "key" || q.name == "authkey"})
          URI(scheme, userInfo, host, port, Some(spreadKey), Some(newQuery), None).toString
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "mail")), port, Some(gmail(_, addr)), _, Some(fragment)) =>
          val msgFragments = fragment.replaceAll("%2F","/").split("/")
          msgFragments.lastOption match {
            case Some(id) if msgFragments.length > 1 => URI(scheme, userInfo, host, port, Some("/mail/" + addr), None, Some("search//" + id)).toString
            case _ => URI(scheme, userInfo, host, port, Some("/mail/" + addr), None, Some(fragment)).toString
          }
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

  object LinkedInNormalizer extends Normalizer {
    val profile = """(.*?id=)([0-9]{1,20})(.*)""".r
    val profileFull = """(.*)(profile/view\?)(.*?id=)([0-9]{1,20})(.*)""".r

    def isDefinedAt(uri: URI) = {
      (uri.host match {
        case Some(Host("com", "linkedin", "www")) => true
        case _ => false
      }) && (uri.raw match {
        case Some(profileFull(_, _, _, id, _)) => true
        case _ => false
      })
    }

    def apply(uri: URI) = {
      uri match {
        case URI(scheme, userInfo, host, port, path, query, _) if query.isDefined => {
          (path, query.getOrElse("").toString) match {
            case (Some("/profile/view"), profile(_, id, _)) => {
              val s = URI(scheme, userInfo, normalize(host), port, path, Some(Query("id="+ id)), None).toString
              log.info("\n\n ============= linkedin normalizer gives uri: " + s)
              s
            }
            case _ => DefaultNormalizer(uri)
          }
        }
        case _ => DefaultNormalizer(uri)
      }
    }

    def normalize(host: Option[Host]) = {
      host match {
        case Some(Host("com", "linkedin")) => Some(Host("com", "linkedin", "www"))
        case _ => host
      }
    }
  }
}
