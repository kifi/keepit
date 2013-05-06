package com.keepit.common.net

import scala.concurrent._

import play.api.libs.json._

class FakeHttpClient(
    requestToResponse: Option[PartialFunction[String, FakeClientResponse]] = None
  ) extends HttpClient {

  override def get(url: String): ClientResponse = assertUrl(url)

  override def post(url: String, body: JsValue): ClientResponse = throw new Exception("this is a GET client")
  def posting(payload: String): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, {body =>
    if(payload != body.toString()) throw new Exception("expected %s doesn't match payload %s".format(payload, body))
  })
  def posting(assertion: String => Unit): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, assertion)

  var callCount = 0

  protected def assertUrl(url: String): ClientResponse = {
    callCount += 1
    val rtr: PartialFunction[String, FakeClientResponse] =
      requestToResponse.getOrElse({ case _: String => FakeClientResponse("") })
    rtr.lift(url).getOrElse(throw new Exception(s"url [$url] did not match"))
  }

  override def longTimeout(): HttpClient = this

  override def postFuture(url: String, body: JsValue): Future[ClientResponse] = Future.successful { post(url, body) }
  override def getFuture(url: String): Future[ClientResponse] = Future.successful { get(url) }
  override def withHeaders(hdrs: (String, String)*): HttpClient = throw new Exception("not supported")
}

class FakeHttpPostClient(requestToResponse: Option[PartialFunction[String, FakeClientResponse]],
                     assertion: String => Unit) extends FakeHttpClient(requestToResponse) {
  override def post(url: String, body: JsValue): ClientResponse = {
    assertion(body.toString())
    assertUrl(url)
  }
  override def get(url: String): ClientResponse = throw new Exception("this is a POST client")
}

case class FakeClientResponse(expectedResponse: String, override val status: Int = 200) extends ClientResponse {
  override def body: String = expectedResponse
  override def json: JsValue = Json.parse(expectedResponse)
}

object FakeClientResponse {
  implicit def stringToFakeResponse(s: String): FakeClientResponse = FakeClientResponse(s)
}
