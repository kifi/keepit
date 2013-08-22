package com.keepit.normalizer

import com.keepit.common.net.{Query, Host, URI}

object LinkedInNormalizer extends StaticNormalizer {
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
