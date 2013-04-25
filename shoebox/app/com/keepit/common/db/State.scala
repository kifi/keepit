package com.keepit.common.db

import play.api.mvc.{PathBindable, QueryStringBindable}
import play.api.libs.json._

case class State[T](value: String) {
  override def toString = value
}

/**
 * A mixin trait for state objects, e.g.
 * {{{
 *   object FollowStates extends States[Follow]
 * }}}
 * @tparam T the type of the model
 */
trait States[T] {
  val ACTIVE = State[T]("active")
  val INACTIVE = State[T]("inactive")
}

class StateException(message: String) extends Exception(message)

object State {
  def format[T]: Format[State[T]] = Format(
    __.read[String].map(State(_)),
    new Writes[State[T]]{ def writes(o: State[T]) = JsString(o.value)}
  )

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[State[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, State[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(State(value))
        case _ => Left("Unable to bind a State")
      }
    }
    override def unbind(key: String, state: State[T]): String = {
      stringBinder.unbind(key, state.value)
    }
  }

  implicit def pathBinder[T] = new PathBindable[State[T]] {
    override def bind(key: String, value: String): Either[String, State[T]] =
      Right(State(value)) // TODO: handle errors if value is malformed

    override def unbind(key: String, state: State[T]): String = state.toString
  }
}
