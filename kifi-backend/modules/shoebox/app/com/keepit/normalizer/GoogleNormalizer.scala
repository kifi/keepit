package com.keepit.normalizer

import com.keepit.common.net.{Query, Param, Host, URI}

object GoogleNormalizer extends StaticNormalizer {

  def isDefinedAt(uri: URI) = {
    uri.host match {
      case Some(Host("com", "google", name)) =>
        name match {
          case "mail" => true
          case "groups" => true
          case "drive" => true
          case "docs" => true
          case "www" => true
          case _ => false
        }
      case _ => false
    }
  }
  val document = """(.*)(/document/d/[^/]+/)(.*)""".r
  val spreadsheet = """(.*)(/spreadsheet/ccc)(.*)""".r
  val file = """(.*)(/file/d/[^/]+/)(.*)""".r
  val gmail = """(/mail/)(.*)""".r
  val driveTabs = Set[String]("my-drive", "shared-with-me", "starred", "recent", "activity", "offline", "all", "trash")

  val searchDropParams = Set[String]("aqs", "sourceid", "espv", "ie", "oq", "es_sm")

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
      case URI(scheme, userInfo, host @ Some(Host("com", "google", "drive")), port, _, _, fragment @ Some(fragmentString)) =>
        if ((fragmentString startsWith "folders/") ||
            (fragmentString startsWith "search/") ||
            (fragmentString startsWith "query?") ||
            (driveTabs contains fragmentString)) {
          URI(scheme, userInfo, host, port, None, None, fragment)
        } else {
          uri
        }
      case URI(scheme, userInfo, host @ Some(Host("com", "google", "www")), port, path @ Some("/search"), query, fragment) =>
        val q = fragment match {
          case Some(f) => Some(Query.parse(f))
          case _ => query.map{ qry => Query(qry.params.filterNot{ case Param(name, _) => searchDropParams.contains(name) }) }
        }
        URI(scheme, userInfo, host, port, path, q, None)
      case _ => uri
    }
  }
}
