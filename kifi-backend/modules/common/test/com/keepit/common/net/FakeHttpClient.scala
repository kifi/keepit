package com.keepit.common.net

import scala.concurrent._

import play.api.libs.json._

class FakeHttpClient(
    requestToResponse: Option[PartialFunction[String, FakeClientResponse]] = None
  ) extends HttpClient {

  override val defaultOnFailure = ignoreFailure

  override def get(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = assertUrl(url)
  override def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a GET client")
  override def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a GET client")
  override def post(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a GET client")
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

  override def postFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = Future.successful { post(url, body) }
  override def getFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = Future.successful { get(url) }
  override def putFuture(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = Future.successful { put(url, body) }
  override def deleteFuture(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): Future[ClientResponse] = Future.successful { delete(url) }
  override def withHeaders(hdrs: (String, String)*): HttpClient = throw new Exception("not supported")
}

class FakeHttpPostClient(requestToResponse: Option[PartialFunction[String, FakeClientResponse]],
  assertion: String => Unit) extends FakeHttpClient(requestToResponse) {
  override def post(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = {
    assertion(body.toString())
    assertUrl(url)
  }
  override def get(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a POST client")
  override def put(url: String, body: JsValue, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a POST client")
  override def delete(url: String, onFailure: => String => PartialFunction[Throwable, Unit] = defaultOnFailure): ClientResponse = throw new Exception("this is a POST client")
}

case class FakeClientResponse(expectedResponse: String, override val status: Int = 200) extends ClientResponse {
  override def body: String = expectedResponse
  override def json: JsValue = Json.parse(expectedResponse)
}

object FakeClientResponse {
  implicit def stringToFakeResponse(s: String): FakeClientResponse = FakeClientResponse(s)
  val fakeAmazonDiscoveryClient: PartialFunction[String, FakeClientResponse] = {
    case s if s.contains("instance-id") => FakeClientResponse("i-f168c1a8")
    case s if s.contains("local-hostname") => FakeClientResponse("ip-10-160-95-26.us-west-1.compute.internal")
    case s if s.contains("public-hostname") => FakeClientResponse("ec2-50-18-183-73.us-west-1.compute.amazonaws.com")
    case s if s.contains("local-ipv4") => FakeClientResponse("10.160.95.26")
    case s if s.contains("public-ipv4") => FakeClientResponse("50.18.183.73")
    case s if s.contains("instance-type") => FakeClientResponse("c1.medium")
    case s if s.contains("placement/availability-zone") => FakeClientResponse("us-west-1b")
    case s if s.contains("security-groups") => FakeClientResponse("default")
    case s if s.contains("ami-id") => FakeClientResponse("ami-1bf9de5e")
    case s if s.contains("ami-launch-index") => FakeClientResponse("0")
  }
}
