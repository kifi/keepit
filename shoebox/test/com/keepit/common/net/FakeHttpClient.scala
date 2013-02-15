package com.keepit.common.net

import play.api.libs.json._
import org.specs2.mutable.Specification
import scala.concurrent.Future

class FakeHttpClient(requestToResponse: Option[PartialFunction[String, String]] = None) extends HttpClient {

  override def get(url: String): ClientResponse = assertUrl(url)

  override def post(url: String, body: JsValue): ClientResponse = throw new Exception("this is a GET client")
  def posting(payload: String): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, {body =>
    if(payload != body.toString()) throw new Exception("expected %s doesn't match payload %s".format(payload, body))
  })
  def posting(assertion: String => Unit): FakeHttpPostClient = new FakeHttpPostClient(requestToResponse, assertion)

  var callCount = 0

  protected def assertUrl(url: String): ClientResponse = {
    callCount += 1
    val rtr: PartialFunction[String, String] = requestToResponse.getOrElse({ case _ => "" })
    new FakeClientResponse(rtr.lift(url).getOrElse {
      throw new Exception("url %s did not match".format(url))
    })
  }

  override def longTimeout(): HttpClient = this

  override def postPromise(url: String, body: JsValue): Future[ClientResponse] = throw new Exception("not supported")
  override def getPromise(url: String): Future[ClientResponse] = throw new Exception("not supported")
  override def withHeaders(hdrs: (String, String)*): HttpClient = throw new Exception("not supported")
}

class FakeHttpPostClient(requestToResponse: Option[PartialFunction[String, String]],
                     assertion: String => Unit) extends FakeHttpClient(requestToResponse) {
  override def post(url: String, body: JsValue): ClientResponse = {
    assertion(body.toString())
    assertUrl(url)
  }
  override def get(url: String): ClientResponse = throw new Exception("this is a POST client")
}

class FakeClientResponse(expectedResponse: String) extends ClientResponse {

  override def body: String = expectedResponse
  override def json: JsValue = Json.parse(expectedResponse)
  override def status: Int = 200

}
