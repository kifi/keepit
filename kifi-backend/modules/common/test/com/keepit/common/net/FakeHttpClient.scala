package com.keepit.common.net

import scala.concurrent._
import scala.xml._
import play.api.libs.ws._

import play.api.libs.json._

class FakeHttpClient(
    requestToResponse: Option[PartialFunction[HttpUri, FakeClientResponse]] = None) extends HttpClient {

  override val defaultFailureHandler = ignoreFailure

  override def get(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = assertUrl(url)
  override def put(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a GET client")
  override def delete(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a GET client")
  override def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a GET client")
  override def postXml(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a POST client")
  override def postText(url: HttpUri, body: String, onFailure: => FailureHandler): com.keepit.common.net.ClientResponse = ???
  override def postTextFuture(url: HttpUri, body: String, onFailure: => FailureHandler): scala.concurrent.Future[com.keepit.common.net.ClientResponse] = ???

  def posting(payload: String): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, { body =>
    if (payload != body.toString()) throw new Exception("expected %s doesn't match payload %s".format(payload, body))
  })
  def posting(assertion: String => Unit): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, assertion)

  var callCount = 0

  protected def assertUrl(url: HttpUri): ClientResponse = {
    callCount += 1
    val rtr: PartialFunction[HttpUri, FakeClientResponse] =
      requestToResponse.getOrElse({ case _: HttpUri => FakeClientResponse("") })
    rtr.lift(url).getOrElse(throw new Exception(s"url [$url] did not match"))
  }

  override def withTimeout(callTimeouts: CallTimeouts): HttpClient = this

  override def postFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = Future.successful { post(url, body) }
  override def postXmlFuture(url: HttpUri, body: NodeSeq, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = Future.successful { postXml(url, body) }
  override def getFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = Future.successful { get(url) }
  override def putFuture(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = Future.successful { put(url, body) }
  override def deleteFuture(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): Future[ClientResponse] = Future.successful { delete(url) }
  override def withHeaders(hdrs: (String, String)*): HttpClient = throw new Exception("not supported")
}

class FakeHttpPostClient(requestToResponse: Option[PartialFunction[HttpUri, FakeClientResponse]],
    assertion: String => Unit) extends FakeHttpClient(requestToResponse) {
  override def post(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = {
    assertion(body.toString())
    assertUrl(url)
  }
  override def get(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a POST client")
  override def put(url: HttpUri, body: JsValue, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a POST client")
  override def delete(url: HttpUri, onFailure: => FailureHandler = defaultFailureHandler): ClientResponse = throw new Exception("this is a POST client")
}

case class FakeClientResponse(expectedResponse: String, override val status: Int = 200) extends ClientResponse {
  def res: Response = ???
  def bytes: Array[Byte] = ???
  def body: String = expectedResponse
  def json: JsValue = if (expectedResponse.trim.isEmpty) JsNull else Json.parse(expectedResponse)
  def xml: NodeSeq = XML.loadString(expectedResponse)
  def request: Request = ???
  def isUp: Boolean = true
}

object FakeClientResponse {
  implicit def stringToFakeResponse(s: String): FakeClientResponse = FakeClientResponse(s)
  val fakeAmazonDiscoveryClient: PartialFunction[HttpUri, FakeClientResponse] = {
    case s if s.url.contains("instance-id") => FakeClientResponse("i-f168c1a8")
    case s if s.url.contains("local-hostname") => FakeClientResponse("ip-10-160-95-26.us-west-1.compute.internal")
    case s if s.url.contains("public-hostname") => FakeClientResponse("ec2-50-18-183-73.us-west-1.compute.amazonaws.com")
    case s if s.url.contains("local-ipv4") => FakeClientResponse("10.160.95.26")
    case s if s.url.contains("public-ipv4") => FakeClientResponse("50.18.183.73")
    case s if s.url.contains("instance-type") => FakeClientResponse("c1.medium")
    case s if s.url.contains("placement/availability-zone") => FakeClientResponse("us-west-1b")
    case s if s.url.contains("security-groups") => FakeClientResponse("default")
    case s if s.url.contains("ami-id") => FakeClientResponse("ami-1bf9de5e")
    case s if s.url.contains("ami-launch-index") => FakeClientResponse("0")
  }

  val emptyFakeHttpClient: PartialFunction[HttpUri, FakeClientResponse] = {
    case _ => FakeClientResponse("Empty fake HTTP Client.", 404)
  }
}
