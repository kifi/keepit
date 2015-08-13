package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.model.{ User, Library }

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class NotificationInfoGenerator @Inject() (
    source: NotificationInfoSource,
    implicit val executionContext: ExecutionContext) {

  implicit def futureConvert[A, B](f: Future[A])(implicit ev: A => B) = f.map(ev)

  private def runAndThen[A, B](andThen: AndThen[A, B], args: Map[String, Object], pickedOne: Boolean): Future[InfoResult[B]] = {
    run(andThen.previous, args, pickedOne).map { result =>
      (andThen.f(result.value), result)
    }.flatMap {
      case (returnsInfo, result) => run(returnsInfo, result.args, result.pickedOne)
    }
  }

  private def getArgs[A](args: Map[String, Object], keyIn: String, pickedOneIn: Boolean): Option[A] = for {
    picked <- Option(pickedOneIn)
    if picked
    key <- Option(keyIn)
    if key != ""
    res <- args.get(key)
  } yield {
    res.asInstanceOf[A]
  }

  def run[A](that: ReturnsInfo[A], args: Map[String, Object], pickedOne: Boolean): Future[InfoResult[A]] = that match {
    case Returns(a) => Future.successful(InfoResult(a, args, pickedOne))

    case Fails(e) => Future.failed(e)

    case andThen: AndThen[_, A] => runAndThen(andThen, args, pickedOne)

    case ReturnsInfo.GetUser(id, name) =>
      implicit val ev = implicitly[User <:< A]
      getArgs(args, name, pickedOne).getOrElse(source.user(id).map { a =>
        InfoResult(a, args, pickedOne)
      })

    case ReturnsInfo.GetLibrary(id, name) =>
      implicit val ev = implicitly[Library <:< A]
      getArgs(args, name, pickedOne).getOrElse(source.library(id).map { a =>
        InfoResult(a, args, pickedOne)
      })

    case ReturnsInfo.PickOne(events) =>
      source.pickOne(events).map { one =>
        InfoResult(one, args, pickedOne = true)
      }

  }

  def runFully[A](that: ReturnsInfo[A], args: Map[String, Object] = Map()): Future[A] = run(that, args, false).map { result =>
    result.value
  }

}

case class InfoResult[A](value: A, args: Map[String, Object], pickedOne: Boolean)
