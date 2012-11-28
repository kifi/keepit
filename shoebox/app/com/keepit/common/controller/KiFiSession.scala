package com.keepit.common.controller

import play.api.mvc._
import play.api._
import play.api.libs.iteratee._
import play.api.libs.Crypto

import scala.annotation._

/**
 * HTTP Session, taken from play.api.mvc.Http.scala
 *
 * Session data are encoded into an HTTP cookie, and can only contain simple `String` values.
 */
case class KiFiSession(data: Map[String, String] = Map.empty[String, String]) {

  /**
   * Optionally returns the session value associated with a key.
   */
  def get(key: String) = data.get(key)

  /**
   * Returns `true` if this session is empty.
   */
  def isEmpty: Boolean = data.isEmpty

  /**
   * Adds a value to the session, and returns a new session.
   *
   * For example:
   * {{{
   * session + ("username" -> "bob")
   * }}}
   *
   * @param kv the key-value pair to add
   * @return the modified session
   */
  def +(kv: (String, String)) = copy(data + kv)

  /**
   * Removes any value from the session.
   *
   * For example:
   * {{{
   * session - "username"
   * }}}
   *
   * @param key the key to remove
   * @return the modified session
   */
  def -(key: String) = copy(data - key)

  /**
   * Retrieves the session value which is associated with the given key.
   */
  def apply(key: String) = data(key)

}

/**
 * Helper utilities to manage the Session cookie.
 */
object KiFiSession extends CookieBaker[KiFiSession] {
  val COOKIE_NAME = "KIFI_SESSION"
  val emptyCookie = new KiFiSession
  override val isSigned = true
  override val secure = true
  override val maxAge = -1
  override val httpOnly = true

  def deserialize(data: Map[String, String]) = new KiFiSession(data)

  def serialize(session: KiFiSession) = session.data
}
