package com.keepit.controllers

sealed abstract case class RequestSource(source: String)

object RequestSource {
  object EXTENSION extends RequestSource("extension")
  object WEBSITE extends RequestSource("website")
  object MOBILE extends RequestSource("mobile")
  object INTERNAL extends RequestSource("internal")
  object UNKNOWN extends RequestSource("unknown")
}
