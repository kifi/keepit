package com.keepit.common.controller

import org.specs2.mutable._
import play.api.mvc.{ RequestHeader, Headers }
import java.util.concurrent.atomic.AtomicLong
import com.google.inject.util.Providers
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.zookeeper.DiscoveryModule
import com.keepit.common.service.ServiceType

case class DummyRequestHeader(id: Long, path: String) extends RequestHeader {
  def tags = Map()
  def uri = ""
  def method = ""
  def version = ""
  def queryString = Map()
  def remoteAddress = ""
  def secure: Boolean = false
  lazy val headers = new Headers { val data = Seq() }
}

class MidFlightRequestsTest extends Specification {

  "MidFlightRequests" should {
    "list largest paths" in {
      val req = new MidFlightRequests(null, Providers.of(MyInstanceInfo(DiscoveryModule.LOCAL_AMZN_INFO, ServiceType.DEV_MODE)))
      val i = new AtomicLong(0)
      val fr1 = DummyRequestHeader(i.incrementAndGet(), "/foo/aar")
      val fr2 = DummyRequestHeader(i.incrementAndGet(), "/foo/bar")
      val fr3 = DummyRequestHeader(i.incrementAndGet(), "/foo/car")
      val fr4 = DummyRequestHeader(i.incrementAndGet(), "/foo/bar")
      val fr5 = DummyRequestHeader(i.incrementAndGet(), "/foo/aar")
      val fr6 = DummyRequestHeader(i.incrementAndGet(), "/foo/bar")
      val info1 = req.comingIn(fr1)
      req.count === 1
      val info2 = req.comingIn(fr2)
      req.count === 2
      val info3 = req.comingIn(fr3)
      req.count === 3
      val info4 = req.comingIn(fr4)
      req.count === 4
      val info5 = req.comingIn(fr5)
      req.count === 5
      val info6 = req.comingIn(fr6)
      req.count === 6
      req.topRequests === "3:/foo/bar,2:/foo/aar,1:/foo/car"
      req.topRequests === "3:/foo/bar,2:/foo/aar,1:/foo/car"
      req.goingOut(info3)
      req.topRequests === "3:/foo/bar,2:/foo/aar"
      req.goingOut(info3)
      req.topRequests === "3:/foo/bar,2:/foo/aar"
      req.goingOut(info1)
      req.topRequests === "3:/foo/bar,1:/foo/aar"
      req.goingOut(info5)
      req.topRequests === "3:/foo/bar"
    }
  }
}
