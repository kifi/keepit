package com.keepit.normalizer

import com.keepit.common.net.{Host, URI}

object AmazonNormalizer extends StaticNormalizer {
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
