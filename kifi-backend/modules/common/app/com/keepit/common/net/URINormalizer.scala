package com.keepit.common.net

import com.keepit.common.logging.Logging
import util.{Failure, Success}

object URINormalizer extends Logging {

  val prenormalizers = Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def normalize(uriString: String): String = try {
    parseAndNormalize(uriString).map(_.toString).getOrElse(uriString)
  } catch { case e: Exception =>
    log.error("uri normalization failed: [%s] caused by [%s]".format(uriString, e.getMessage))
    uriString
  }

  def parseAndNormalize(uriString: String) : Option[URI] = URI.parse(uriString) match {
    case Success(uri) =>
      val prenormalizer = prenormalizers.find(_.isDefinedAt(uri)).getOrElse(throw new Exception("failed to find a pre-normalizer"))
      Some(SmartNormalizer(prenormalizer(uri)))

    case Failure(e) =>
      log.error("uri normalization failed: [%s] caused by [%s]".format(uriString, e.getMessage))
      None
  }

  trait Normalizer extends PartialFunction[URI, URI]

  object SmartNormalizer extends Normalizer {
    def isDefinedAt(uri: URI) = true // smart normalizer should always be applicable
    def apply(uri: URI) = uri // ok, it is actually pretty dumb at this point
  }

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
            URI(scheme, userInfo, host, port, path, newQuery, None)
          } catch {
            case e: Exception =>
              log.warn("uri normalization failed: [%s] caused by [%s]".format(uri.raw, e.getMessage))
              uri
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
            case Some(product(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None)
            case Some(product2(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some("/dp"+ key), None, None)
            case Some(productReviews(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None)
            case Some(profile(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None)
            case Some(memberReviews(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None)
            case Some(wishlist(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(key), None, None)
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
          URI(scheme, userInfo, host, port, Some(docKey + "edit"), query, None)
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(file(_, fileKey, _)), query, fragment) =>
          URI(scheme, userInfo, host, port, Some(fileKey + "edit"), query, None)
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "docs")), port, Some(spreadsheet(_, spreadKey, _)), Some(query), fragment) =>
          val newQuery = Query(query.params.filter{ q => q.name == "key" || q.name == "authkey"})
          URI(scheme, userInfo, host, port, Some(spreadKey), Some(newQuery), None)
        case URI(scheme, userInfo, host @ Some(Host("com", "google", "mail")), port, Some(gmail(_, addr)), _, Some(fragment)) =>
          val msgFragments = fragment.replaceAll("%2F","/").split("/")
          msgFragments.lastOption match {
            case Some(id) if msgFragments.length > 1 => URI(scheme, userInfo, host, port, Some("/mail/" + addr), None, Some("search//" + id))
            case _ => URI(scheme, userInfo, host, port, Some("/mail/" + addr), None, Some(fragment))
          }
        case _ => uri
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
          URI(scheme, None, host, port, path, Some(newQuery), None)
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
            case (Some("/profile/view"), profile(_, id, _)) => URI(scheme, userInfo, normalize(host), port, path, Some(Query("id="+ id)), None)
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
