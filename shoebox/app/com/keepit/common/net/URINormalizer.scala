package com.keepit.common.net

import com.keepit.common.logging.Logging
import scala.collection.immutable.SortedMap

object URINormalizer extends Logging {
  
  val stopParams = Set(
    "jsessionid", "phpsessid", "aspsessionid",
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "zenid")
  
  def normalize(uriString: String) = {
    uriString.replace(" ", "%20") match {
      case URI(scheme, userInfo, host, port, path, query) => URI(scheme, userInfo, host, port, path, query)
      case uriString @_ => uriString // normalization failed
    }
  }
  
  object URI {
    def unapply(uriString: String): Option[(Option[String], Option[String], Option[String], Int, Option[String], Option[String])] = {
      try {
        val uri = try {
          // preprocess illegal chars
          val preprocessed = uriString.replace("|", "%7C")
          
          new java.net.URI(preprocessed).normalize()
        } catch {
          case e: java.net.URISyntaxException =>
            // there may be malformed escape
            val fixedUriString = fixMalformedEscape(uriString)
            new java.net.URI(fixedUriString).normalize()
        }
        val scheme = normalizeScheme(Option(uri.getScheme))
        val userInfo = Option(uri.getUserInfo)
        val host = normalizeHost(Option(uri.getHost))
        val port = normalizePort(uri.getPort)
        val (path, query) = normalizePathAndQuery(Option(uri.getPath), Option(uri.getRawQuery))
        Some((scheme, userInfo, host, port, path, query))
      } catch {
        case e: Exception =>
          log.error("uri normalization failed: [%s]".format(uriString))
          None
      }
    }
    
    def apply(scheme: Option[String], userInfo: Option[String], host: Option[String], port: Int, path: Option[String], query: Option[String]) = {
      query.foldLeft(new java.net.URI(scheme.orNull, userInfo.orNull, host.orNull, normalizePort(port), path.orNull, null, null).toString){
        (base, query) => base + "?" + query
      }
    }
    
    val twoHexDigits = """\p{XDigit}\p{XDigit}""".r
    val encodedPercent = java.net.URLEncoder.encode("%", "UTF-8")
    
    def fixMalformedEscape(uriString: String) = {
      uriString.split("%", -1) match {
        case Array(first, rest @ _*) => 
          rest.foldLeft(first){ (str, piece) => str + twoHexDigits.findPrefixOf(piece).map(_ => "%").getOrElse(encodedPercent) + piece }
      }
    }
    def normalizeScheme(scheme: Option[String]) = scheme.map(_.toLowerCase)
    
    def normalizeHost(host: Option[String]) = host.map(_.toLowerCase)
    
    def normalizePort(port: Int) = if (port == 80) -1 else port
    
    def normalizePathAndQuery(path: Option[String], query: Option[String]) = {
      val normalizedPath = normalizePath(path)
      val normalizedQuery = normalizeQuery(query)
      (normalizedPath, normalizedQuery) match {
        case (None, None) => (None, None)
        case (None, query @ Some(_)) => (Some("/"), query)
        case (Some("/"), None) => (None, None)
        case pathAndQuery @ _ => pathAndQuery
      }
    }
    
    val slashDotDot = """^(/\.\.)+""".r

    val defaultPage = """/(index|default)\.(html|htm|asp|aspx|php|php3|php4|phtml|cfm|cgi|jsp|jsf|jspx|jspa)$""".r
    
    def normalizePath(path: Option[String]) = {
      path.flatMap{ path =>
        var path2 = slashDotDot.replaceFirstIn(path.trim, "")
        defaultPage.findFirstMatchIn(path2.toLowerCase).foreach{ m =>
          val delta = path2.length - path2.toLowerCase.length // in case the case conversion changed the length
          path2 = path2.substring(0, m.start + delta) + "/"
        }
        path2 match {
          case "" => None
          case path => Some(path.replace("%7E", "~").replace(" ", "%20"))
        }
      }
    } 
    
    def normalizeQuery(query: Option[String]) = {
      query.flatMap{ query =>
        query.trim match {
          case Query(params @ _*) => Query(params)
          case _ =>
            log.error("query normalization failed: [%s]".format(query))
            Some(query)
        }
      }
    }
    
  }
  
  object Query {
    def unapplySeq(query: String): Option[Seq[String]] = {
      var pairs = SortedMap.empty[String, Option[String]]
      query.split("&").foreach{
        case "" =>
        case NameValuePair(name, value) => pairs += (name -> value)
      }
      pairs = pairs.filter{ case (name, value) => !stopParams.contains(name) }
      Some(pairs.iterator.map{ case (k, v) =>  NameValuePair(k, v) }.toSeq)
    }
    
    def apply(params: Seq[String]): Option[String] = {
      if (params.size > 0) Some(params.mkString("&"))
      else None
    }
  }

  object NameValuePair {
    def unapply(param: String): Option[(String, Option[String])] = {
      param.split("=", 2) match {
        case Array(name) => Some((normalize(name), None))
        case Array(name, value) => Some((normalize(name), Some(normalize(value))))
        case _ => Some((normalize(param), None))
      }
    }
    
    def apply(name: String, value: Option[String]): String = {
      if (value.isDefined) (name + "=" + value.get) else name
    }
    
    def normalize(string: String) = {
      try {
        val decoded = java.net.URLDecoder.decode(string.replace("+", "%20"), "UTF-8") // java URLDecoder does not replace "+" with a space
        java.net.URLEncoder.encode(decoded, "UTF-8")
      } catch {
        case e: Exception =>
          log.error("parameter normalization failed: [%s]".format(string))
          string
      }
    }
  }
}