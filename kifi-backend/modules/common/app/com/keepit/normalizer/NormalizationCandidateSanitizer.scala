package com.keepit.normalizer

import com.keepit.common.net.{ Query, URI }
import org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4

import scala.util.Try

object NormalizationCandidateSanitizer {
  private val quotedString = """"(.+)"""".r
  def validateCandidateUrl(url: String, candidateUrl: String): Option[String] = {
    for {
      actualCandidateUrl <- Option(candidateUrl) collect {
        case quotedString(urlString) => urlString
        case urlString if urlString.nonEmpty => urlString
      }
      absoluteUrl <- URI.absoluteUrl(url, actualCandidateUrl)
      parsed <- URI.safelyParse(absoluteUrl)
      parsedUrl <- Some(parsed.toString) if {

        // Our URI parser and Java's are not strictly equivalent.
        lazy val failsJavaParser = Try { java.net.URI.create(parsedUrl) } isFailure

        // Question marks are allowed in query parameter names and values, but their presence
        // in a canonical URL usually indicates a bad url.
        lazy val hasQuestionMarkInQueryParameters = (parsed.query.exists(_.params.exists(p => p.name.contains('?') || p.value.exists(_.contains('?')))))

        // A common site error is copying the page URL directly into a canoncial URL tag, escaped an extra time.
        lazy val isEscapedUrl = (absoluteUrl.length > url.length && unescapeHtml4(absoluteUrl) == url)

        // A less common but also cascading site error is URL-encoding query parameters an extra time.
        lazy val hasEscapedQueryParameter = parsed.query.exists(_.params.exists(_.value.exists(_.contains("%25")))) && decodePercents(parsed) == url

        !(failsJavaParser || hasQuestionMarkInQueryParameters || isEscapedUrl || hasEscapedQueryParameter)
      }
    } yield parsedUrl
  }

  private def decodePercents(uri: URI): String = { // just doing query parameter values for now
    URI(
      raw = None,
      scheme = uri.scheme,
      userInfo = uri.userInfo,
      host = uri.host,
      port = uri.port,
      path = uri.path,
      query = uri.query.map(q => Query(q.params.map(p => p.copy(value = p.value.map(_.replace("%25", "%")))))),
      fragment = uri.fragment
    ).toString
  }

}
