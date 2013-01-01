package com.keepit.common.db

import play.api.mvc.{PathBindable, QueryStringBindable}

case class State[T](val value: String) {
  override def toString = value
}

class StateException(message: String) extends Exception(message)

object State {

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
