package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import org.apache.http.protocol.HttpRequestExecutor
import org.apache.http.{ HttpRequest, HttpResponse }

/**
 * Let the executor process the content only if the response status code is good (in super class)
 * and we think we can parse the content by looking at the content type.
 * The code is used in the following way:
 * <pre>
 *                 if (conn.isResponseAvailable(this.waitForContinue)) {
 *                   response = conn.receiveResponseHeader();
 *                   if (canResponseHaveBody(request, response)) {
 *                       conn.receiveResponseEntity(response); // this is where HttpRequestExecutor actually gets the body content
 *                   }
 *                   ...
 * </pre>
 */
class ContentAwareHttpRequestExecutor extends HttpRequestExecutor with Logging {

  override def canResponseHaveBody(request: HttpRequest, response: HttpResponse): Boolean = {
    val contentTypes = response.getHeaders("Content-Type").map(_.getValue)
    val isGood = super.canResponseHaveBody(request, response) && parsableContent(contentTypes)
    if (!isGood) log.warn(s"dropping scrape candidate with content types: ${contentTypes.mkString(",")} coming from $response")
    isGood
  }

  /**
   * if the response didn't specify content type, give it the benefit of the doubt
   */
  def parsableContent(contentTypes: Seq[String]): Boolean = contentTypes.isEmpty || contentTypes.exists(parsableContent)

  private def parsableContent(contentType: String): Boolean = {
    contentType != "application/ogg" && contentType != "application/mp4" && !contentType.startsWith("audio/") && !contentType.startsWith("video/")
  }
}
