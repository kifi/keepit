package com.keepit.notify.info

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.model.{ User, Library }
import com.keepit.notify.info.ReturnsInfo.PickOne

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class NotificationInfoGenerator @Inject() (
    source: NotificationInfoSource,
    implicit val executionContext: ExecutionContext) {

  private def runAndThen[A, B](andThen: AndThen[A, B], args: Args, pickedOne: Boolean): Future[InfoResult[B]] = {
    run(andThen.previous, args, pickedOne).map { result =>
      (andThen.f(result.value), result)
    }.flatMap {
      case (returnsInfo, result) => run(returnsInfo, result.args, result.pickedOne)
    }
  }

  private def getArgs[E](args: Args, keyIn: String, pickedOneIn: Boolean): Option[E] = for {
    picked <- Option(pickedOneIn)
    if picked
    key <- Option(keyIn)
    if key != ""
    res <- args.get(key)
  } yield {
    res.asInstanceOf[E]
  }

  def run[A](that: ReturnsInfo[A], args: Args, pickedOne: Boolean): Future[InfoResult[A]] = that match {

    case Returns(a) => Future.successful(InfoResult(a, args, pickedOne))

    case Fails(e) => Future.failed(e)

    case andThen: AndThen[_, A] => runAndThen(andThen, args, pickedOne)

    case ArgAction(arg, fromSource) =>
      getArgs(args, arg, pickedOne).getOrElse(fromSource(source).map { a =>
        InfoResult(a, args, pickedOne)
      })

    case PickOne(events: Set[_]) =>
      Future.successful(InfoResult(events.head, args, pickedOne = true))

  }

  def runFully[A](that: ReturnsInfo[A], args: Args = Map()): Future[A] = run(that, args, pickedOne = alse).map { result =>
    result.value
  }

}

case class InfoResult[A](value: A, args: Args, pickedOne: Boolean)
