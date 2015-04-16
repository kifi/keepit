package com.keepit.normalizer

import com.keepit.common.net.{Query, URI}
import org.apache.commons.lang3.StringEscapeUtils._

object NormalizationCandidateSanitizer {

  def validateCandidateUrl(url: String, candidateUrl: String): Option[String] = {
    URI.sanitize(url, candidateUrl).flatMap { parsed =>
      val sanitizedCandidateUrl = parsed.toString()

      // Question marks are allowed in query parameter names and values, but their presence
      // in a canonical URL usually indicates a bad url.
      lazy val hasQuestionMarkInQueryParameters = (parsed.query.exists(_.params.exists(p => p.name.contains('?') || p.value.exists(_.contains('?')))))

      // A common site error is copying the page URL directly into a canoncial URL tag, escaped an extra time.
      lazy val isEscapedUrl = (sanitizedCandidateUrl.length > url.length && unescapeHtml4(sanitizedCandidateUrl) == url)

      // A less common but also cascading site error is URL-encoding query parameters an extra time.
      lazy val hasEscapedQueryParameter = parsed.query.exists(_.params.exists(_.value.exists(_.contains("%25")))) && decodePercents(parsed) == url

      if (hasQuestionMarkInQueryParameters || isEscapedUrl || hasEscapedQueryParameter) None else Some(sanitizedCandidateUrl)
    }
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
