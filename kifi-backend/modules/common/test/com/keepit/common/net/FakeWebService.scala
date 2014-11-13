package com.keepit.common.net

import com.google.inject.Singleton
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{ JsString, JsValue }
import play.api.libs.ws._
import play.libs.ws.WSClient
import com.keepit.common.core._

import scala.concurrent.Future
import scala.xml.Elem

case class FakeWSRequestHolder(
    url: String,
    calc: Option[WSSignatureCalculator] = None,
    queryString: Map[String, Seq[String]] = Map.empty,
    method: String = "GET",
    followRedirects: Option[Boolean] = None,
    body: WSBody = EmptyBody,
    requestTimeout: Option[Int] = None,
    virtualHost: Option[String] = None,
    proxyServer: Option[WSProxyServer] = None,
    auth: Option[(String, String, WSAuthScheme)] = None,
    headers: Map[String, Seq[String]] = Map.empty,
    var response: Option[WSResponse] = None,
    var streamResponse: Option[(WSResponseHeaders, Enumerator[Array[Byte]])] = None) extends WSRequestHolder {

  def withHeaders(hdrs: (String, String)*): WSRequestHolder = this.copy(headers = hdrs.map(r => r._1 -> Seq(r._2)).toMap)
  override def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequestHolder = this.copy(auth = Some((username, password, scheme)))
  override def withQueryString(parameters: (String, String)*): WSRequestHolder = this.copy(queryString = parameters.map(r => r._1 -> Seq(r._2)).toMap)
  override def execute(): Future[WSResponse] = Future.successful(response.getOrElse(emptyResponse))
  override def sign(calc: WSSignatureCalculator): WSRequestHolder = this.copy(calc = Some(calc))
  override def stream(): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = Future.successful {
    streamResponse.getOrElse((emptyResponseHeader, emptyResponseBody))
  }
  override def withVirtualHost(vh: String): WSRequestHolder = this.copy(virtualHost = Some(vh))
  override def withMethod(method: String): WSRequestHolder = this.copy(method = method)
  override def withRequestTimeout(timeout: Int): WSRequestHolder = this.copy(requestTimeout = Some(timeout))
  override def withProxyServer(proxyServer: WSProxyServer): WSRequestHolder = this.copy(proxyServer = Some(proxyServer))
  override def withFollowRedirects(follow: Boolean): WSRequestHolder = this.copy(followRedirects = Some(follow))
  override def withBody(body: WSBody): WSRequestHolder = this.copy(body = body)

  // Testing methods:
  val emptyResponse: WSResponse = new FakeWSResponse()
  def setResponse(resp: WSResponse): Unit = {
    response = Some(resp)
  }

  val emptyResponseHeader = new FakeWSResponseHeaders()
  val emptyResponseBody = Enumerator(Array[Byte](0xc, 0x0, 0xf, 0xf, 0xe, 0xe))

  def setStreamResponse(hdrs: WSResponseHeaders, body: Enumerator[Array[Byte]]): Unit = {
    streamResponse = Some((hdrs, body))
  }

}

class FakeWSResponseHeaders extends WSResponseHeaders {
  override def status: Int = 200
  override def headers: Map[String, Seq[String]] = Map.empty
}

class FakeWSResponse extends WSResponse {
  override def statusText: String = "OK"
  override def underlying[T]: T = ???
  override def xml: Elem = <result></result>
  override def body: String = "blank"
  override def header(key: String): Option[String] = None
  override def cookie(name: String): Option[WSCookie] = None
  override def cookies: Seq[WSCookie] = Seq.empty
  override def status: Int = 200
  override def json: JsValue = JsString("blank")
  override def allHeaders: Map[String, Seq[String]] = Map.empty
}

@Singleton
class FakeWebService extends WebService {
  private var _responseGenerator: Option[String => WSResponse] = None
  def setGlobalResponse(f: String => WSResponse): Unit = {
    _responseGenerator = Some(f)
  }

  private var _streamResponseGenerator: Option[String => (WSResponseHeaders, Enumerator[Array[Byte]])] = None
  def setGlobalStreamResponse(f: String => (WSResponseHeaders, Enumerator[Array[Byte]])): Unit = {
    _streamResponseGenerator = Some(f)
  }

  def url(url: String): WSRequestHolder = {
    FakeWSRequestHolder(url) |> { resp =>
      if (_responseGenerator.isDefined) {
        resp.setResponse(_responseGenerator.get(url))
        resp
      } else resp
    } |> { resp =>
      if (_streamResponseGenerator.isDefined) {
        val (header, body) = _streamResponseGenerator.get(url)
        resp.setStreamResponse(header, body)
        resp
      } else resp
    }
  }
}
