package com.keepit.common.net

import play.api.libs.json._
import org.specs2.mutable.Specification
import play.api.libs.concurrent.Promise

class FakeHttpClient(expectedUrl: Option[String] = None, 
                     expectedResponse: Option[String] = None) extends HttpClient {
  
  override def get(url: String): ClientResponse = assertUrl(url)
  
  override def post(url: String, body: JsValue): ClientResponse = throw new Exception("this is a GET client")
  def posting(payload: String): FakeHttpPostClient = new FakeHttpPostClient(expectedUrl, expectedResponse, {body => 
    if(payload != body.toString()) throw new Exception("expected %s doesn't match payload %s".format(payload, body))
  }) 
  def posting(assertion: String => Unit): FakeHttpPostClient = new FakeHttpPostClient(expectedUrl, expectedResponse, assertion)
  
  var callCount = 0
  
  protected def assertUrl(url: String): ClientResponse = {
    callCount += 1
    expectedUrl map { expected => if(expected != url) throw new Exception("expected %s doesn't match url %s".format(expected, url)) }
    new FakeClientResponse(expectedResponse)
  }
  
  override def longTimeout(): HttpClient = this

  override def postPromise(url: String, body: JsValue): Promise[ClientResponse] = throw new Exception("not supported")
  override def getPromise(url: String): Promise[ClientResponse] = throw new Exception("not supported")
  override def withHeaders(hdrs: (String, String)*): HttpClient = throw new Exception("not supported")
}

class FakeHttpPostClient(expectedUrl: Option[String] = None, 
                     expectedResponse: Option[String] = None,
                     assertion: String => Unit) extends FakeHttpClient(expectedUrl, expectedResponse) {
  override def post(url: String, body: JsValue): ClientResponse = {
    assertion(body.toString())
    assertUrl(url)
  }
  override def get(url: String): ClientResponse = throw new Exception("this is a POST client")
}

class FakeClientResponse(expectedResponse: Option[String] = None) extends ClientResponse {
  
  override def body: String = expectedResponse.getOrElse(throw new Exception("no text body provided"))
  override def json: JsValue = Json.parse(expectedResponse.getOrElse(throw new Exception("no text json provided")))
  override def status: Int = 200
  
}
