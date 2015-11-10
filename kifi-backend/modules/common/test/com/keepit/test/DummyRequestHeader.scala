package com.keepit.test

import play.api.mvc.{ Cookie, Cookies, Headers, RequestHeader }

import scala.collection.mutable

case class DummyRequestHeader(id: Long, path: String) extends RequestHeader {
  def tags = Map()

  def uri = ""

  def method = ""

  def version = ""

  def queryString = Map()

  def remoteAddress = ""

  def secure: Boolean = false

  val mutableHeaders = mutable.ArrayBuffer[(String, Seq[String])]()
  lazy val headers = new Headers {
    override protected val data: Seq[(String, Seq[String])] = mutableHeaders
  }

  val mutableCookies = mutable.Map[String, String]()

  override lazy val cookies: Cookies = new Cookies {
    private def toCookie(name: String, value: String) = new Cookie(name, value)

    override def get(name: String): Option[Cookie] = mutableCookies.get(name).map(value => toCookie(name, value))

    override def foreach[U](f: (Cookie) => U): Unit = {
      mutableCookies.toSeq.map { case (name, value) => toCookie(name, value) }.foreach(f)
    }
  }
}
