package com.keepit.normalizer

import com.keepit.common.net.{ Host, URI }

object AmazonNormalizer extends StaticNormalizer {
  val product = """.*/(dp|gp/product|gp/aw/d)(/[^/]+)(/.*)""".r
  val productReviews = """.*/(product-reviews|gp/aw/cr)(/[^/]+)(/.*)""".r
  val profile = """(.*)(/gp/pdp/profile/[^/]+)(/.*)""".r
  val memberReviews = """(.*)(/gp/cdp/member-reviews/[^/]+)(/.*)""".r
  val wishlist = """(.*)(/wishlist/[^/]+)(/.*)""".r

  def isDefinedAt(uri: URI) = {
    (uri.host match {
      case Some(Host(_, "amazon")) => true
      case Some(Host(_, "amazon", "www")) => true
      case Some(Host(_, _, "amazon")) => true
      case Some(Host(_, _, "amazon", "www")) => true
      case _ => false
    }) && (uri.path match {
      case Some(product(_, _, _)) => true
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
          case Some(product(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(s"/dp$key"), None, None)
          case Some(productReviews(_, key, _)) => URI(scheme, userInfo, normalize(host), port, Some(s"/product-reviews$key"), None, None)
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
      case Some(Host(country, subdomain, "amazon")) => Some(Host(country, subdomain, "amazon", "www"))
      case Some(Host(country, "amazon")) => Some(Host(country, "amazon", "www"))
      case _ => host
    }
  }
}
