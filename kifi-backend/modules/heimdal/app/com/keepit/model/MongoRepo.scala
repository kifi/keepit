package com.keepit.model

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }

import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging

case class MongoInsertBufferFullException() extends java.lang.Throwable

trait MongoRepo[T] {
  protected val airbrake: AirbrakeNotifier
}

trait BufferedMongoRepo[T] extends MongoRepo[T] with Logging { //Convoluted?
  val warnBufferSize: Int
  val maxBufferSize: Int

  val bufferSize = new AtomicLong(0)
  val hasWarned = new AtomicBoolean(false)
}

object CustomBSONHandlers {}
