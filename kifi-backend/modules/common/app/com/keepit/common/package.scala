package com.keepit.common

import java.util.concurrent.locks.ReentrantLock

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe._
import scala.util.Try
import scala.compat.Platform.{ currentTime => now }

package object core extends com.keepit.common.Implicits with UsefulFunctions {

  // Helpful types
  type ?=>[-A, +B] = PartialFunction[A, B]
  type switch = scala.annotation.switch
  type tailrec = scala.annotation.tailrec
  type File = java.io.File
}

trait UsefulFunctions {
  private type ATypeTag[+A] = TypeTag[A @uncheckedVariance]
  def ?!?[A](implicit tag: ATypeTag[A]): A =
    throw new NotImplementedError(s"unimplemented value of type ${tag.tpe}")

  def SafeOpt[A](value: => A): Option[A] = Try(Option(value)).toOption.flatten

  object extras {
    def debounce[A, B](wait: FiniteDuration)(f: A => B): A => Option[B] = {
      var (isRunning, lastStopTime) = (new ReentrantLock(), Long.MinValue)
      (input: A) => {
        if (isRunning.tryLock()) {
          try {
            if (lastStopTime + wait.toMillis <= now) {
              try {
                Some(f(input))
              } finally {
                lastStopTime = now
              }
            } else {
              None
            }
          } finally {
            isRunning.unlock()
          }
        } else {
          None
        }
      }
    }
  }
}
